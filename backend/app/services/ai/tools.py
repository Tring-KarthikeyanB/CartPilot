"""The agent's tools: JSON-schema specs for the model, plus a validating executor.

Safety model:
- Arguments are validated with strict Pydantic models before anything runs (unknown fields rejected).
- Tools can only read the catalogue and mutate *this session's* cart (session_id is bound server-side).
- There is no payment, SQL, shell, file or network tool. Checkout requires an explicit user action in the app.
- Results come from the catalogue/cart services; prices and totals are never taken from the model.
- Failures are returned to the model as structured errors so it can recover, never raised to the user raw.
"""

from dataclasses import dataclass, field
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.core.errors import AppError
from app.schemas.cart import MAX_QUANTITY_PER_ITEM, BudgetCheck, CartSummary
from app.schemas.product import Category, Product
from app.services.ai.provider import ToolSpec
from app.services.cart.cart_service import CartService
from app.services.products.catalog_service import CatalogService, SortOrder

MAX_SEARCH_RESULTS = 8

# ---------------- argument models ----------------


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SearchArgs(_Args):
    query: str | None = Field(default=None, max_length=200)
    category: Category | None = None
    min_price: float | None = Field(default=None, ge=0)
    max_price: float | None = Field(default=None, gt=0)
    min_rating: float | None = Field(default=None, ge=0, le=5)
    in_stock_only: bool = True
    sort: Literal["relevance", "price_asc", "price_desc", "rating"] = "relevance"
    limit: int = Field(default=5, ge=1, le=MAX_SEARCH_RESULTS)


class CompareArgs(_Args):
    product_ids: list[str] = Field(min_length=2, max_length=5)


class AddArgs(_Args):
    product_id: str = Field(min_length=1, max_length=20)
    quantity: int = Field(default=1, ge=1, le=MAX_QUANTITY_PER_ITEM)


class RemoveArgs(_Args):
    product_id: str = Field(min_length=1, max_length=20)


class ReplaceArgs(_Args):
    old_product_id: str = Field(min_length=1, max_length=20)
    new_product_id: str = Field(min_length=1, max_length=20)


class EmptyArgs(_Args):
    pass


class BudgetArgs(_Args):
    budget: float | None = Field(default=None, gt=0, le=100_000)


# ---------------- specs shown to the model ----------------

_CATEGORY_ENUM = [c.value for c in Category]

TOOL_SPECS: list[ToolSpec] = [
    ToolSpec(
        name="search_products",
        description=(
            "Search the CartPilot catalogue. This is the ONLY source of products: never mention a product, "
            "price or ID that did not come from this tool. Use `category` whenever the user names a product type. "
            "Returns up to `limit` products with id, name, price (USD), rating, stock status and features."
        ),
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Keywords, e.g. 'wireless quiet keyboard', 'noise cancelling'"},
                "category": {"type": "string", "enum": _CATEGORY_ENUM},
                "min_price": {"type": "number", "description": "Minimum unit price in USD"},
                "max_price": {"type": "number", "description": "Maximum unit price in USD"},
                "min_rating": {"type": "number", "description": "Minimum rating 0-5"},
                "in_stock_only": {"type": "boolean", "description": "Default true; only purchasable items"},
                "sort": {"type": "string", "enum": ["relevance", "price_asc", "price_desc", "rating"]},
                "limit": {"type": "integer", "description": f"1-{MAX_SEARCH_RESULTS}, default 5"},
            },
        },
    ),
    ToolSpec(
        name="compare_products",
        description="Compare 2-5 catalogue products by price, rating, value and features. Use IDs from search results.",
        parameters={
            "type": "object",
            "properties": {"product_ids": {"type": "array", "items": {"type": "string"}, "description": "2-5 product IDs"}},
            "required": ["product_ids"],
        },
    ),
    ToolSpec(
        name="add_to_cart",
        description="Add a catalogue product to the user's cart. Returns the updated cart with backend-calculated totals.",
        parameters={
            "type": "object",
            "properties": {
                "product_id": {"type": "string"},
                "quantity": {"type": "integer", "description": f"1-{MAX_QUANTITY_PER_ITEM}, default 1"},
            },
            "required": ["product_id"],
        },
    ),
    ToolSpec(
        name="remove_from_cart",
        description="Remove a product (all units) from the user's cart.",
        parameters={"type": "object", "properties": {"product_id": {"type": "string"}}, "required": ["product_id"]},
    ),
    ToolSpec(
        name="replace_product",
        description="Swap a product in the cart for another catalogue product, keeping its quantity and position.",
        parameters={
            "type": "object",
            "properties": {
                "old_product_id": {"type": "string", "description": "Product currently in the cart"},
                "new_product_id": {"type": "string", "description": "Replacement product from search results"},
            },
            "required": ["old_product_id", "new_product_id"],
        },
    ),
    ToolSpec(
        name="get_cart_summary",
        description="Get the current cart: items, quantities, subtotal, stored budget and remaining budget.",
        parameters={"type": "object", "properties": {}},
    ),
    ToolSpec(
        name="validate_budget",
        description=(
            "Check the cart subtotal against a budget and get cheaper same-category swap suggestions. "
            "Pass `budget` when the user states or changes their budget (it is saved for the session); "
            "omit it to check against the saved budget."
        ),
        parameters={
            "type": "object",
            "properties": {"budget": {"type": "number", "description": "Total budget in USD"}},
        },
    ),
]

_ARG_MODELS: dict[str, type[_Args]] = {
    "search_products": SearchArgs,
    "compare_products": CompareArgs,
    "add_to_cart": AddArgs,
    "remove_from_cart": RemoveArgs,
    "replace_product": ReplaceArgs,
    "get_cart_summary": EmptyArgs,
    "validate_budget": BudgetArgs,
}

CART_MUTATING_TOOLS = {"add_to_cart", "remove_from_cart", "replace_product"}


# ---------------- compact result views (keep tokens low, keep the model grounded) ----------------


def _product_view(p: Product) -> dict[str, Any]:
    return {
        "id": p.id,
        "name": p.name,
        "brand": p.brand,
        "category": p.category.value,
        "price_usd": float(p.price),
        "rating": p.rating,
        "review_count": p.review_count,
        "stock_status": p.stock_status.value,
        "features": p.features,
        "description": p.description,
    }


def _cart_view(c: CartSummary) -> dict[str, Any]:
    return {
        "items": [
            {"product_id": i.product_id, "name": i.name, "unit_price_usd": i.unit_price, "quantity": i.quantity, "line_total_usd": i.line_total}
            for i in c.items
        ],
        "item_count": c.item_count,
        "subtotal_usd": c.subtotal,
        "budget_usd": c.budget,
        "budget_remaining_usd": c.budget_remaining,
        "over_budget": c.over_budget,
    }


def _budget_view(b: BudgetCheck) -> dict[str, Any]:
    return b.model_dump()


# ---------------- executor ----------------


@dataclass
class ToolOutcome:
    name: str
    ok: bool
    content: dict[str, Any]  # what the model sees
    summary: str  # short human-readable line for the app ("Added X to cart")
    product_ids: list[str] = field(default_factory=list)  # catalogue products surfaced by this call
    budget: BudgetCheck | None = None


class ToolExecutor:
    def __init__(self, catalog: CatalogService, carts: CartService, session_id: str):
        self.catalog = catalog
        self.carts = carts
        self.session_id = session_id

    def execute(self, name: str, raw_args: dict[str, Any] | None) -> ToolOutcome:
        model = _ARG_MODELS.get(name)
        if model is None:
            return self._error(name, "unknown_tool", f"Tool '{name}' does not exist. Available: {', '.join(_ARG_MODELS)}.")
        try:
            args = model.model_validate(raw_args or {})
        except ValidationError as e:
            problems = "; ".join(f"{'.'.join(str(x) for x in err['loc']) or 'args'}: {err['msg']}" for err in e.errors())
            return self._error(name, "invalid_arguments", f"Invalid arguments for {name}: {problems}")
        try:
            return getattr(self, f"_{name}")(args)
        except AppError as e:
            return self._error(name, e.code, e.message)

    @staticmethod
    def _error(name: str, code: str, message: str) -> ToolOutcome:
        return ToolOutcome(name=name, ok=False, content={"ok": False, "error": {"code": code, "message": message}}, summary=message)

    # --- tools ---

    def _search_products(self, a: SearchArgs) -> ToolOutcome:
        results = self.catalog.search(
            query=a.query, category=a.category, min_price=a.min_price, max_price=a.max_price,
            min_rating=a.min_rating, in_stock_only=a.in_stock_only, sort=SortOrder(a.sort), limit=a.limit,
        )
        what = a.query or (a.category.value.replace("_", " ") if a.category else "products")
        return ToolOutcome(
            name="search_products", ok=True,
            content={"ok": True, "count": len(results), "products": [_product_view(p) for p in results]},
            summary=f"Searched {what}: {len(results)} found",
            product_ids=[p.id for p in results],
        )

    def _compare_products(self, a: CompareArgs) -> ToolOutcome:
        r = self.catalog.compare(a.product_ids)
        return ToolOutcome(
            name="compare_products", ok=True,
            content={
                "ok": True,
                "products": [
                    {**_product_view(c.product), "value_score": c.value_score, "unique_features": c.unique_features}
                    for c in r.products
                ],
                "shared_features": r.shared_features,
                "cheapest_id": r.cheapest_id,
                "highest_rated_id": r.highest_rated_id,
                "best_value_id": r.best_value_id,
                "summary": r.summary,
            },
            summary=f"Compared {len(r.products)} products",
            product_ids=[c.product.id for c in r.products],
        )

    def _add_to_cart(self, a: AddArgs) -> ToolOutcome:
        product = self.catalog.get(a.product_id)
        cart = self.carts.add_item(self.session_id, product.id, a.quantity)
        qty = f"{a.quantity} × " if a.quantity > 1 else ""
        return ToolOutcome(
            name="add_to_cart", ok=True, content={"ok": True, "added": product.id, "cart": _cart_view(cart)},
            summary=f"Added {qty}{product.name}", product_ids=[product.id],
        )

    def _remove_from_cart(self, a: RemoveArgs) -> ToolOutcome:
        product = self.catalog.get(a.product_id)
        cart = self.carts.remove_item(self.session_id, product.id)
        return ToolOutcome(
            name="remove_from_cart", ok=True, content={"ok": True, "removed": product.id, "cart": _cart_view(cart)},
            summary=f"Removed {product.name}",
        )

    def _replace_product(self, a: ReplaceArgs) -> ToolOutcome:
        old = self.catalog.get(a.old_product_id)
        new = self.catalog.get(a.new_product_id)
        cart = self.carts.replace_item(self.session_id, old.id, new.id)
        diff = new.price - old.price
        return ToolOutcome(
            name="replace_product", ok=True,
            content={"ok": True, "replaced": old.id, "with": new.id, "unit_price_change_usd": float(diff), "cart": _cart_view(cart)},
            summary=f"Replaced {old.name} with {new.name}", product_ids=[new.id],
        )

    def _get_cart_summary(self, _: EmptyArgs) -> ToolOutcome:
        cart = self.carts.get_summary(self.session_id)
        return ToolOutcome(
            name="get_cart_summary", ok=True, content={"ok": True, "cart": _cart_view(cart)},
            summary=f"Checked cart ({cart.item_count} items, ${cart.subtotal:.2f})",
        )

    def _validate_budget(self, a: BudgetArgs) -> ToolOutcome:
        if a.budget is not None:
            self.carts.set_budget(self.session_id, a.budget)
        check = self.carts.check_budget(self.session_id)
        return ToolOutcome(
            name="validate_budget", ok=True, content={"ok": True, **_budget_view(check)},
            summary=check.message, budget=check,
        )
