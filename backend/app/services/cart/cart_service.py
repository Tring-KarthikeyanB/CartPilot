"""Session carts (in-memory). All prices and totals are computed here from the catalogue —
never taken from the client or the AI model."""

import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from decimal import Decimal

from app.core.errors import ConflictError, InvalidInputError, NotFoundError
from app.core.money import CURRENCY, money
from app.repositories.product_repository import ProductRepository
from app.schemas.cart import (
    MAX_QUANTITY_PER_ITEM,
    SESSION_ID_PATTERN,
    BudgetCheck,
    CartItem,
    CartSummary,
    Saving,
)
from app.schemas.product import Product

MAX_SESSIONS = 5_000
MAX_BUDGET = Decimal("100000")
_SESSION_RE = re.compile(SESSION_ID_PATTERN)


@dataclass
class _Cart:
    items: "OrderedDict[str, int]" = field(default_factory=OrderedDict)
    budget: Decimal | None = None
    touched: float = field(default_factory=time.monotonic)


def validate_session_id(session_id: str) -> str:
    if not isinstance(session_id, str) or not _SESSION_RE.match(session_id):
        raise InvalidInputError(
            "session_id must be 8-64 characters of letters, digits, '-' or '_'.", code="invalid_session_id"
        )
    return session_id


class CartService:
    def __init__(self, products: ProductRepository):
        self.products = products
        self._carts: "OrderedDict[str, _Cart]" = OrderedDict()
        self._lock = threading.RLock()

    # ---------- internals ----------

    def _cart(self, session_id: str) -> _Cart:
        validate_session_id(session_id)
        cart = self._carts.get(session_id)
        if cart is None:
            cart = _Cart()
            self._carts[session_id] = cart
            while len(self._carts) > MAX_SESSIONS:
                self._carts.popitem(last=False)
        cart.touched = time.monotonic()
        self._carts.move_to_end(session_id)
        return cart

    @staticmethod
    def _check_quantity(quantity: int) -> None:
        if not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= MAX_QUANTITY_PER_ITEM:
            raise InvalidInputError(
                f"Quantity must be a whole number between 1 and {MAX_QUANTITY_PER_ITEM}.", code="invalid_quantity"
            )

    @staticmethod
    def _require_available(product: Product) -> None:
        if not product.available:
            raise ConflictError(f"{product.name} ({product.id}) is out of stock.", code="out_of_stock")

    def _require_in_cart(self, cart: _Cart, product_id: str) -> str:
        pid = self.products.get(product_id).id  # 404 if not a catalogue product
        if pid not in cart.items:
            raise NotFoundError(f"Product '{pid}' is not in the cart.", code="not_in_cart")
        return pid

    # ---------- mutations ----------

    def add_item(self, session_id: str, product_id: str, quantity: int = 1) -> CartSummary:
        self._check_quantity(quantity)
        product = self.products.get(product_id)
        self._require_available(product)
        with self._lock:
            cart = self._cart(session_id)
            new_qty = cart.items.get(product.id, 0) + quantity
            if new_qty > MAX_QUANTITY_PER_ITEM:
                raise InvalidInputError(
                    f"At most {MAX_QUANTITY_PER_ITEM} of each product per order.", code="invalid_quantity"
                )
            cart.items[product.id] = new_qty
            return self._summary(session_id, cart)

    def set_quantity(self, session_id: str, product_id: str, quantity: int) -> CartSummary:
        with self._lock:
            cart = self._cart(session_id)
            pid = self._require_in_cart(cart, product_id)
            if quantity == 0:
                del cart.items[pid]
            else:
                self._check_quantity(quantity)
                cart.items[pid] = quantity
            return self._summary(session_id, cart)

    def remove_item(self, session_id: str, product_id: str) -> CartSummary:
        with self._lock:
            cart = self._cart(session_id)
            pid = self._require_in_cart(cart, product_id)
            del cart.items[pid]
            return self._summary(session_id, cart)

    def replace_item(self, session_id: str, old_product_id: str, new_product_id: str) -> CartSummary:
        new_product = self.products.get(new_product_id)
        self._require_available(new_product)
        with self._lock:
            cart = self._cart(session_id)
            old_id = self._require_in_cart(cart, old_product_id)
            if old_id == new_product.id:
                raise InvalidInputError("The replacement must be a different product.", code="same_product")
            qty = cart.items[old_id]
            # Keep the original position in the cart.
            rebuilt: OrderedDict[str, int] = OrderedDict()
            for pid, q in cart.items.items():
                if pid == old_id:
                    rebuilt[new_product.id] = min(qty + cart.items.get(new_product.id, 0), MAX_QUANTITY_PER_ITEM)
                elif pid != new_product.id:
                    rebuilt[pid] = q
            cart.items = rebuilt
            return self._summary(session_id, cart)

    def clear(self, session_id: str) -> CartSummary:
        with self._lock:
            cart = self._cart(session_id)
            cart.items.clear()
            return self._summary(session_id, cart)

    def set_budget(self, session_id: str, amount: float | Decimal | None) -> CartSummary:
        with self._lock:
            cart = self._cart(session_id)
            cart.budget = self._parse_budget(amount)
            return self._summary(session_id, cart)

    @staticmethod
    def _parse_budget(amount: float | Decimal | None) -> Decimal | None:
        if amount is None:
            return None
        value = money(amount)
        if value <= 0 or value > MAX_BUDGET:
            raise InvalidInputError(f"Budget must be between $0.01 and ${MAX_BUDGET}.", code="invalid_budget")
        return value

    # ---------- queries ----------

    def get_summary(self, session_id: str) -> CartSummary:
        with self._lock:
            return self._summary(session_id, self._cart(session_id))

    def subtotal(self, session_id: str) -> Decimal:
        with self._lock:
            return self._subtotal(self._cart(session_id))

    def _subtotal(self, cart: _Cart) -> Decimal:
        return money(sum((self.products.get(pid).price * qty for pid, qty in cart.items.items()), Decimal("0")))

    def _summary(self, session_id: str, cart: _Cart) -> CartSummary:
        items = []
        for pid, qty in cart.items.items():
            p = self.products.get(pid)
            items.append(
                CartItem(
                    product_id=p.id,
                    name=p.name,
                    category=p.category,
                    image_url=p.image_url,
                    stock_status=p.stock_status,
                    unit_price=float(p.price),
                    quantity=qty,
                    line_total=float(money(p.price * qty)),
                )
            )
        subtotal = self._subtotal(cart)
        remaining = money(cart.budget - subtotal) if cart.budget is not None else None
        return CartSummary(
            session_id=session_id,
            items=items,
            item_count=sum(cart.items.values()),
            subtotal=float(subtotal),
            currency=CURRENCY,
            budget=float(cart.budget) if cart.budget is not None else None,
            budget_remaining=float(remaining) if remaining is not None else None,
            over_budget=remaining is not None and remaining < 0,
        )

    def check_budget(self, session_id: str, budget: float | Decimal | None = None) -> BudgetCheck:
        """Compare the cart with `budget` (or the session's stored budget) and suggest cheaper swaps."""
        with self._lock:
            cart = self._cart(session_id)
            limit = self._parse_budget(budget) if budget is not None else cart.budget
            subtotal = self._subtotal(cart)
            suggestions = self._cheaper_alternatives(cart)

        if limit is None:
            return BudgetCheck(
                budget=None, subtotal=float(subtotal), remaining=None, within_budget=True, overage=0.0,
                suggestions=suggestions, message=f"No budget set. Cart subtotal is ${subtotal}.",
            )
        remaining = money(limit - subtotal)
        within = remaining >= 0
        if within:
            message = f"Cart subtotal ${subtotal} is within the ${limit} budget (${remaining} left)."
        else:
            potential = money(sum((Decimal(str(s.savings)) for s in suggestions), Decimal("0")))
            message = (
                f"Cart subtotal ${subtotal} is ${-remaining} over the ${limit} budget."
                + (f" Swapping to the suggested alternatives could save up to ${potential}." if suggestions else "")
            )
        return BudgetCheck(
            budget=float(limit), subtotal=float(subtotal), remaining=float(remaining), within_budget=within,
            overage=float(max(-remaining, Decimal("0"))), suggestions=suggestions, message=message,
        )

    def _cheaper_alternatives(self, cart: _Cart, min_rating: float = 4.0) -> list[Saving]:
        """Best cheaper, in-stock, well-rated product in the same category for each cart line."""
        out: list[Saving] = []
        catalogue = self.products.all()
        for pid, qty in cart.items.items():
            current = self.products.get(pid)
            candidates = [
                p for p in catalogue
                if p.category == current.category and p.id not in cart.items and p.available
                and p.price < current.price and p.rating >= min_rating
            ]
            if not candidates:
                continue
            # Prefer the highest-rated cheaper option; tie-break on bigger savings.
            best = max(candidates, key=lambda p: (p.rating, current.price - p.price))
            out.append(
                Saving(
                    replace_product_id=current.id, replace_name=current.name,
                    with_product_id=best.id, with_name=best.name, with_rating=best.rating,
                    savings=float(money((current.price - best.price) * qty)),
                )
            )
        out.sort(key=lambda s: -s.savings)
        return out
