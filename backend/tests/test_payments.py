import pytest

from app.api.deps import get_payment_service
from app.core.config import Settings, get_settings
from app.core.errors import ConflictError, InvalidInputError, NotFoundError
from app.main import app
from app.schemas.payment import PaymentStatus
from app.services.payments.payment_service import (
    PaymentDeclinedError,
    PaymentProviderError,
    PaymentService,
    PaymentsUnavailableError,
)
from app.services.payments.paypal_client import PayPalClient, PayPalError
from tests.conftest import SESSION
from tests.fake_paypal import FakePayPal

BASE = "http://10.0.2.2:8000/"


@pytest.fixture
def paypal():
    return FakePayPal()


@pytest.fixture
def payments(paypal, carts):
    client = PayPalClient("cid", "secret", "https://api-m.sandbox.paypal.com", transport=paypal.transport())
    return PaymentService(client, carts)


@pytest.fixture
def filled_cart(carts):
    carts.add_item(SESSION, "kb-005")          # 79.99
    carts.add_item(SESSION, "ms-001", 2)       # 2 x 19.99
    return carts.get_summary(SESSION)          # 119.97


def create(payments, total=119.97, confirmed=True):
    return payments.create_order(SESSION, total, confirmed, BASE)


# ---------- create ----------

def test_create_order_uses_server_cart_amount(payments, paypal, filled_cart):
    order = create(payments)
    assert order.status == PaymentStatus.awaiting_approval
    assert order.approve_url.startswith("https://www.sandbox.paypal.com/checkoutnow?token=")
    assert order.amount == 119.97 and [i.product_id for i in order.items] == ["kb-005", "ms-001"]

    body = paypal.orders[order.order_id]["body"]
    unit = body["purchase_units"][0]
    assert body["intent"] == "CAPTURE"
    assert unit["amount"] == {"currency_code": "USD", "value": "119.97",
                              "breakdown": {"item_total": {"currency_code": "USD", "value": "119.97"}}}
    assert [(i["sku"], i["quantity"], i["unit_amount"]["value"]) for i in unit["items"]] == [("kb-005", "1", "79.99"), ("ms-001", "2", "19.99")]
    ctx = body["payment_source"]["paypal"]["experience_context"]
    assert ctx["return_url"] == "http://10.0.2.2:8000/api/v1/payments/paypal/return"
    assert ctx["cancel_url"] == "http://10.0.2.2:8000/api/v1/payments/paypal/cancel"
    assert ctx["user_action"] == "PAY_NOW" and ctx["shipping_preference"] == "NO_SHIPPING"
    create_req = next(r for r in paypal.requests if r.url.path == "/v2/checkout/orders")
    assert create_req.headers["PayPal-Request-Id"].startswith("create-")


def test_create_requires_explicit_confirmation(payments, filled_cart):
    with pytest.raises(InvalidInputError) as e:
        create(payments, confirmed=False)
    assert e.value.code == "approval_required"


def test_create_rejects_stale_client_total(payments, filled_cart):
    with pytest.raises(ConflictError) as e:
        create(payments, total=0.01)  # client can't choose the amount
    assert e.value.code == "cart_changed" and e.value.details == {"current_total": 119.97}


def test_create_rejects_empty_cart(payments):
    with pytest.raises(ConflictError) as e:
        create(payments, total=1)
    assert e.value.code == "cart_empty"


def test_create_maps_paypal_failure(payments, paypal, filled_cart):
    paypal.fail_create_with = 500
    with pytest.raises(PaymentProviderError):
        create(payments)


def test_payments_not_configured(carts, filled_cart):
    with pytest.raises(PaymentsUnavailableError):
        PaymentService(None, carts).create_order(SESSION, 119.97, True, BASE)


# ---------- capture & verification ----------

def test_capture_before_approval_is_rejected(payments, filled_cart):
    order = create(payments)
    with pytest.raises(ConflictError) as e:
        payments.capture_order(SESSION, order.order_id)
    assert e.value.code == "payment_not_approved" and e.value.details["approve_url"] == order.approve_url
    assert payments.carts.get_summary(SESSION).item_count == 3  # nothing removed


def test_successful_capture_is_verified_and_clears_purchased_items(payments, paypal, carts, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    receipt = payments.capture_order(SESSION, order.order_id)
    assert receipt.status == PaymentStatus.completed and receipt.paypal_status == "COMPLETED"
    assert receipt.capture_id == f"CAP{order.order_id}" and receipt.amount == 119.97 and receipt.currency == "USD"
    assert receipt.payer_email == "buyer@example.com" and receipt.payer_name == "Sandbox Buyer"
    assert carts.get_summary(SESSION).items == []


def test_capture_is_idempotent(payments, paypal, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    first = payments.capture_order(SESSION, order.order_id)
    second = payments.capture_order(SESSION, order.order_id)
    assert first == second and paypal.orders[order.order_id]["captures"] == 1


def test_already_captured_at_paypal_is_recovered(payments, paypal, filled_cart):
    """e.g. the first capture succeeded at PayPal but the response was lost."""
    order = create(payments)
    paypal.approve(order.order_id)
    payments.capture_order(SESSION, order.order_id)
    payments.find(order.order_id).status = PaymentStatus.awaiting_approval  # simulate lost local state
    payments.find(order.order_id).receipt = None
    receipt = payments.capture_order(SESSION, order.order_id)
    assert receipt.status == PaymentStatus.completed


def test_capture_uses_stable_idempotency_key(payments, paypal, filled_cart):
    order = create(payments)
    with pytest.raises(ConflictError):
        payments.capture_order(SESSION, order.order_id)
    paypal.approve(order.order_id)
    payments.capture_order(SESSION, order.order_id)
    assert len(set(paypal.capture_request_ids)) == 1 and paypal.capture_request_ids[0].startswith("capture-")


def test_amount_mismatch_fails_verification(payments, paypal, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    paypal.capture_amount_override = "1.00"
    with pytest.raises(PaymentProviderError) as e:
        payments.capture_order(SESSION, order.order_id)
    assert e.value.code == "payment_verification_failed"
    assert payments.find(order.order_id).status == PaymentStatus.failed
    assert payments.carts.get_summary(SESSION).item_count == 3  # not treated as paid


def test_pending_capture_is_not_success(payments, paypal, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    paypal.capture_status = "PENDING"
    with pytest.raises(ConflictError) as e:
        payments.capture_order(SESSION, order.order_id)
    assert e.value.code == "payment_pending"
    assert payments.find(order.order_id).status != PaymentStatus.completed


def test_declined_instrument(payments, paypal, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    paypal.decline_capture = True
    with pytest.raises(PaymentDeclinedError) as e:
        payments.capture_order(SESSION, order.order_id)
    assert e.value.status_code == 402 and e.value.details["approve_url"] == order.approve_url


def test_capture_rejects_other_sessions_and_unknown_orders(payments, paypal, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    with pytest.raises(NotFoundError):
        payments.capture_order("attacker-session", order.order_id)
    with pytest.raises(NotFoundError):
        payments.capture_order(SESSION, "UNKNOWN00001")
    assert paypal.orders[order.order_id]["captures"] == 0


def test_items_added_after_checkout_stay_in_cart(payments, paypal, carts, filled_cart):
    order = create(payments)
    carts.add_item(SESSION, "da-005")
    paypal.approve(order.order_id)
    receipt = payments.capture_order(SESSION, order.order_id)
    assert receipt.amount == 119.97  # charged what the user approved
    assert [i.product_id for i in carts.get_summary(SESSION).items] == ["da-005"]


def test_token_is_cached_and_refreshed_on_401(payments, paypal, filled_cart):
    create(payments)
    create(payments)
    assert paypal.token_calls == 1
    paypal.revoke_token_once = True
    create(payments)
    assert paypal.token_calls == 2


def test_bad_credentials_surface_as_paypal_error(paypal):
    client = PayPalClient("wrong", "creds", transport=paypal.transport())
    with pytest.raises(PayPalError) as e:
        client.get_order("X")
    assert e.value.status == 401


# ---------- status & cancel ----------

def test_order_status_and_cancel(payments, paypal, filled_cart):
    order = create(payments)
    s = payments.order_status(SESSION, order.order_id)
    assert (s.status, s.paypal_status, s.can_capture) == (PaymentStatus.awaiting_approval, "PAYER_ACTION_REQUIRED", False)

    assert payments.mark_cancelled(order.order_id)
    assert payments.order_status(SESSION, order.order_id).status == PaymentStatus.cancelled

    paypal.approve(order.order_id)  # buyer went back and approved after all
    assert payments.order_status(SESSION, order.order_id).can_capture is True
    payments.capture_order(SESSION, order.order_id)
    done = payments.order_status(SESSION, order.order_id)
    assert done.status == PaymentStatus.completed and done.receipt and not done.can_capture


# ---------- API ----------

@pytest.fixture
def pay_client(client, payments):
    app.dependency_overrides[get_payment_service] = lambda: payments
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, public_base_url="")
    return client


def test_payment_api_happy_path(pay_client, paypal, filled_cart):
    r = pay_client.post("/api/v1/payments/create-order", json={"session_id": SESSION, "expected_total": 119.97, "user_confirmed": True})
    assert r.status_code == 200
    order = r.json()
    body = paypal.orders[order["order_id"]]["body"]
    assert body["payment_source"]["paypal"]["experience_context"]["return_url"] == "http://testserver/api/v1/payments/paypal/return"

    r = pay_client.post("/api/v1/payments/capture-order", json={"session_id": SESSION, "order_id": order["order_id"]})
    assert r.status_code == 409 and r.json()["error"]["code"] == "payment_not_approved"

    paypal.approve(order["order_id"])
    r = pay_client.get(f"/api/v1/payments/orders/{order['order_id']}", params={"session_id": SESSION})
    assert r.json()["can_capture"] is True

    r = pay_client.post("/api/v1/payments/capture-order", json={"session_id": SESSION, "order_id": order["order_id"]})
    assert r.status_code == 200 and r.json()["status"] == "completed" and r.json()["amount"] == 119.97
    assert pay_client.get(f"/api/v1/cart/{SESSION}").json()["items"] == []


def test_payment_api_rejects_client_amount_and_missing_confirmation(pay_client, filled_cart):
    r = pay_client.post("/api/v1/payments/create-order", json={"session_id": SESSION, "expected_total": 5, "user_confirmed": True})
    assert r.status_code == 409 and r.json()["error"]["details"]["current_total"] == 119.97
    r = pay_client.post("/api/v1/payments/create-order", json={"session_id": SESSION, "expected_total": 119.97, "user_confirmed": False})
    assert r.status_code == 422 and r.json()["error"]["code"] == "approval_required"
    r = pay_client.post("/api/v1/payments/capture-order", json={"session_id": SESSION, "order_id": "bad id"})
    assert r.status_code == 422


def test_return_page_hands_off_to_app_without_capturing(pay_client, paypal, payments, filled_cart):
    order = create(payments)
    paypal.approve(order.order_id)
    r = pay_client.get("/api/v1/payments/paypal/return", params={"token": order.order_id, "PayerID": "X"})
    assert r.status_code == 200
    assert f"intent://payment/return?order_id={order.order_id}#Intent;scheme=cartpilot;package=com.cartpilot;end" in r.text
    assert f"cartpilot://payment/return?order_id={order.order_id}" in r.text
    assert paypal.orders[order.order_id]["captures"] == 0  # landing here never marks anything paid
    assert payments.find(order.order_id).status == PaymentStatus.awaiting_approval


def test_cancel_page_marks_cancelled(pay_client, payments, filled_cart):
    order = create(payments)
    r = pay_client.get("/api/v1/payments/paypal/cancel", params={"token": order.order_id})
    assert "cartpilot://payment/cancel?order_id=" in r.text and "No money was taken" in r.text
    assert payments.find(order.order_id).status == PaymentStatus.cancelled


def test_return_page_escapes_hostile_token(pay_client):
    r = pay_client.get("/api/v1/payments/paypal/return", params={"token": "<script>alert(1)</script>"})
    assert r.status_code == 200 and "<script>alert" not in r.text and "order_id=" not in r.text


def test_payments_unavailable_api(client, carts, filled_cart):
    app.dependency_overrides[get_payment_service] = lambda: PaymentService(None, carts)
    r = client.post("/api/v1/payments/create-order", json={"session_id": SESSION, "expected_total": 119.97, "user_confirmed": True})
    assert r.status_code == 503 and r.json()["error"]["code"] == "payments_unavailable"
