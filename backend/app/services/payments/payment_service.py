"""Checkout with PayPal Orders v2.

Rules enforced here:
- The charge amount is computed from the server-side cart (catalogue prices), never from the client or the AI.
- Creating an order requires explicit user confirmation and the total the user saw must match the server total.
- A payment is "completed" only after the backend captured it with PayPal and verified the captured
  amount, currency and order identity. Redirects/deep links never mark anything as paid.
- Capture is idempotent (PayPal-Request-Id + stored receipt), so retries can't double charge.
"""

import logging
import threading
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal

from app.core.errors import AppError, ConflictError, InvalidInputError, NotFoundError
from app.core.money import CURRENCY, money, money_str
from app.schemas.payment import (
    CreateOrderResponse,
    OrderItem,
    OrderStatusResponse,
    PaymentReceipt,
    PaymentStatus,
)
from app.services.cart.cart_service import CartService, validate_session_id
from app.services.payments.paypal_client import PayPalClient, PayPalError

log = logging.getLogger(__name__)

MAX_RECORDS = 5_000
BRAND_NAME = "CartPilot AI"
_DECLINE_ISSUES = {"INSTRUMENT_DECLINED", "PAYER_CANNOT_PAY", "TRANSACTION_REFUSED", "PAYER_ACCOUNT_RESTRICTED"}
_NOT_APPROVED_ISSUES = {"ORDER_NOT_APPROVED", "PAYER_ACTION_REQUIRED"}


class PaymentsUnavailableError(AppError):
    status_code = 503
    code = "payments_unavailable"


class PaymentProviderError(AppError):
    status_code = 502
    code = "payment_provider_error"


class PaymentDeclinedError(AppError):
    status_code = 402
    code = "payment_declined"


@dataclass
class _PaymentRecord:
    payment_id: str
    order_id: str
    session_id: str
    amount: Decimal
    currency: str
    items: list[OrderItem]
    approve_url: str
    status: PaymentStatus = PaymentStatus.awaiting_approval
    receipt: PaymentReceipt | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)


class PaymentService:
    def __init__(self, paypal: PayPalClient | None, carts: CartService):
        self.paypal = paypal
        self.carts = carts
        self._records: "OrderedDict[str, _PaymentRecord]" = OrderedDict()
        self._guard = threading.Lock()

    def _require_paypal(self) -> PayPalClient:
        if self.paypal is None:
            raise PaymentsUnavailableError("PayPal is not configured on the server (missing PAYPAL_CLIENT_ID/SECRET).")
        return self.paypal

    def _record(self, order_id: str, session_id: str) -> _PaymentRecord:
        validate_session_id(session_id)
        with self._guard:
            rec = self._records.get(order_id)
        # Same error for "unknown" and "belongs to another session" so order IDs can't be probed.
        if rec is None or rec.session_id != session_id:
            raise NotFoundError(f"Payment for order '{order_id}' was not found.", code="payment_not_found")
        return rec

    def find(self, order_id: str) -> _PaymentRecord | None:
        with self._guard:
            return self._records.get(order_id)

    # ---------- create ----------

    def create_order(
        self, session_id: str, expected_total: float, user_confirmed: bool, return_base_url: str
    ) -> CreateOrderResponse:
        paypal = self._require_paypal()
        if not user_confirmed:
            raise InvalidInputError("Please confirm the payment before continuing to PayPal.", code="approval_required")

        cart = self.carts.get_summary(session_id)
        if not cart.items:
            raise ConflictError("Your cart is empty.", code="cart_empty")
        unavailable = [i.name for i in cart.items if i.stock_status.value == "out_of_stock"]
        if unavailable:
            raise ConflictError(f"Some items are out of stock: {', '.join(unavailable)}.", code="out_of_stock")

        amount = money(cart.subtotal)
        if money(expected_total) != amount:
            raise ConflictError(
                f"Your cart total changed to ${amount}. Please review it before paying.",
                code="cart_changed", details={"current_total": float(amount)},
            )

        items = [
            OrderItem(product_id=i.product_id, name=i.name, quantity=i.quantity, unit_price=i.unit_price, line_total=i.line_total)
            for i in cart.items
        ]
        payment_id = uuid.uuid4().hex
        base = return_base_url.rstrip("/")
        payload = {
            "intent": "CAPTURE",
            "purchase_units": [
                {
                    "reference_id": "default",
                    "custom_id": payment_id,
                    "description": f"{BRAND_NAME} order",
                    "amount": {
                        "currency_code": CURRENCY,
                        "value": money_str(amount),
                        "breakdown": {"item_total": {"currency_code": CURRENCY, "value": money_str(amount)}},
                    },
                    "items": [
                        {
                            "name": i.name[:127],
                            "sku": i.product_id,
                            "quantity": str(i.quantity),
                            "category": "PHYSICAL_GOODS",
                            "unit_amount": {"currency_code": CURRENCY, "value": money_str(Decimal(str(i.unit_price)))},
                        }
                        for i in items
                    ],
                }
            ],
            "payment_source": {
                "paypal": {
                    "experience_context": {
                        "brand_name": BRAND_NAME,
                        "user_action": "PAY_NOW",
                        "shipping_preference": "NO_SHIPPING",
                        "return_url": f"{base}/api/v1/payments/paypal/return",
                        "cancel_url": f"{base}/api/v1/payments/paypal/cancel",
                    }
                }
            },
        }
        try:
            order = paypal.create_order(payload, request_id=f"create-{payment_id}")
        except PayPalError as e:
            raise PaymentProviderError(f"PayPal could not create the order ({e.issue or e.name or e.status}).") from e

        order_id = order.get("id", "")
        approve_url = next((l["href"] for l in order.get("links", []) if l.get("rel") in ("payer-action", "approve")), None)
        if not order_id or not approve_url:
            raise PaymentProviderError("PayPal returned an order without an approval link.")

        rec = _PaymentRecord(payment_id, order_id, session_id, amount, CURRENCY, items, approve_url)
        with self._guard:
            self._records[order_id] = rec
            while len(self._records) > MAX_RECORDS:
                self._records.popitem(last=False)
        log.info("Created PayPal order %s for session %s amount %s", order_id, session_id, amount)
        return CreateOrderResponse(
            order_id=order_id, approve_url=approve_url, status=rec.status, amount=float(amount), currency=CURRENCY, items=items
        )

    # ---------- capture ----------

    def capture_order(self, session_id: str, order_id: str) -> PaymentReceipt:
        paypal = self._require_paypal()
        rec = self._record(order_id, session_id)
        with rec.lock:
            if rec.status == PaymentStatus.completed and rec.receipt:
                return rec.receipt  # idempotent
            try:
                data = paypal.capture_order(order_id, request_id=f"capture-{rec.payment_id}")
            except PayPalError as e:
                if e.issue == "ORDER_ALREADY_CAPTURED":
                    data = self._get_order(order_id)
                elif e.issue in _NOT_APPROVED_ISSUES:
                    raise ConflictError(
                        "The payment hasn't been approved in PayPal yet.", code="payment_not_approved",
                        details={"approve_url": rec.approve_url},
                    ) from e
                elif e.issue in _DECLINE_ISSUES:
                    raise PaymentDeclinedError(
                        "PayPal declined this payment method. Please try again with another funding source.",
                        details={"approve_url": rec.approve_url},
                    ) from e
                elif e.status == 404:
                    raise NotFoundError("PayPal could not find this order.", code="payment_not_found") from e
                else:
                    raise PaymentProviderError(f"PayPal capture failed ({e.issue or e.name or e.status}).") from e

            receipt = self._verify_capture(rec, data)
            rec.receipt = receipt
            rec.status = PaymentStatus.completed
        self._remove_purchased_items(rec)
        log.info("Captured and verified PayPal order %s capture %s", order_id, receipt.capture_id)
        return receipt

    def _get_order(self, order_id: str) -> dict:
        try:
            return self._require_paypal().get_order(order_id)
        except PayPalError as e:
            raise PaymentProviderError(f"Could not fetch the PayPal order ({e.issue or e.name or e.status}).") from e

    def _verify_capture(self, rec: _PaymentRecord, data: dict) -> PaymentReceipt:
        def fail(reason: str) -> PaymentProviderError:
            rec.status = PaymentStatus.failed
            log.error("PayPal capture verification failed for %s: %s", rec.order_id, reason)
            return PaymentProviderError(
                "We couldn't verify this payment with PayPal. You have not been charged twice; please contact support.",
                code="payment_verification_failed",
            )

        if data.get("id") != rec.order_id:
            raise fail(f"order id mismatch: {data.get('id')}")
        units = data.get("purchase_units") or []
        unit = units[0] if units else {}
        captures = (unit.get("payments") or {}).get("captures") or []
        capture = next((c for c in captures if c.get("status") == "COMPLETED"), None)
        if capture is None:
            pending = next((c for c in captures if c.get("status") == "PENDING"), None)
            if pending is not None:
                raise ConflictError(
                    "PayPal is still processing this payment. Please check again shortly.", code="payment_pending"
                )
            raise fail(f"no completed capture (order status {data.get('status')})")
        if data.get("status") != "COMPLETED":
            raise fail(f"order status {data.get('status')}")
        amount = capture.get("amount") or {}
        if amount.get("currency_code") != rec.currency or money(amount.get("value", "0")) != rec.amount:
            raise fail(f"amount mismatch: {amount} vs {rec.amount} {rec.currency}")
        if unit.get("custom_id") not in (None, rec.payment_id):
            raise fail("custom_id mismatch")

        payer = data.get("payer") or {}
        name = payer.get("name") or {}
        full_name = " ".join(p for p in (name.get("given_name"), name.get("surname")) if p) or None
        return PaymentReceipt(
            order_id=rec.order_id,
            capture_id=capture.get("id", ""),
            status=PaymentStatus.completed,
            paypal_status=data.get("status", ""),
            amount=float(rec.amount),
            currency=rec.currency,
            items=rec.items,
            payer_email=payer.get("email_address"),
            payer_name=full_name,
            captured_at=datetime.now(timezone.utc),
        )

    def _remove_purchased_items(self, rec: _PaymentRecord) -> None:
        """Take the purchased quantities out of the cart (items added after checkout started stay)."""
        cart = {i.product_id: i.quantity for i in self.carts.get_summary(rec.session_id).items}
        for item in rec.items:
            if item.product_id in cart:
                remaining = max(cart[item.product_id] - item.quantity, 0)
                self.carts.set_quantity(rec.session_id, item.product_id, remaining)

    # ---------- status / cancel ----------

    def order_status(self, session_id: str, order_id: str) -> OrderStatusResponse:
        rec = self._record(order_id, session_id)
        paypal_status = None
        if rec.status != PaymentStatus.completed:
            paypal_status = self._get_order(order_id).get("status")
        else:
            paypal_status = rec.receipt.paypal_status if rec.receipt else "COMPLETED"
        return OrderStatusResponse(
            order_id=order_id,
            status=rec.status,
            paypal_status=paypal_status,
            can_capture=rec.status != PaymentStatus.completed and paypal_status == "APPROVED",
            approve_url=rec.approve_url if rec.status != PaymentStatus.completed else None,
            amount=float(rec.amount),
            currency=rec.currency,
            receipt=rec.receipt,
        )

    def mark_cancelled(self, order_id: str) -> bool:
        """Buyer clicked cancel on PayPal. Purely informational: nothing was charged."""
        rec = self.find(order_id)
        if rec is None:
            return False
        with rec.lock:
            if rec.status == PaymentStatus.awaiting_approval:
                rec.status = PaymentStatus.cancelled
        return True
