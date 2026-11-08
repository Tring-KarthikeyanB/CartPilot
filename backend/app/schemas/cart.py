from pydantic import BaseModel, Field

from app.schemas.product import Category, StockStatus

SESSION_ID_PATTERN = r"^[A-Za-z0-9_-]{8,64}$"
MAX_QUANTITY_PER_ITEM = 10


class CartItem(BaseModel):
    product_id: str
    name: str
    category: Category
    image_url: str
    stock_status: StockStatus
    unit_price: float
    quantity: int
    line_total: float


class CartSummary(BaseModel):
    session_id: str
    items: list[CartItem]
    item_count: int
    subtotal: float
    currency: str
    budget: float | None
    budget_remaining: float | None
    over_budget: bool


class Saving(BaseModel):
    replace_product_id: str
    replace_name: str
    with_product_id: str
    with_name: str
    with_rating: float
    savings: float


class BudgetCheck(BaseModel):
    budget: float | None
    subtotal: float
    remaining: float | None
    within_budget: bool
    overage: float
    suggestions: list[Saving]
    message: str


# --- requests ---

class AddItemRequest(BaseModel):
    product_id: str
    quantity: int = Field(default=1, ge=1, le=MAX_QUANTITY_PER_ITEM)


class UpdateQuantityRequest(BaseModel):
    quantity: int = Field(ge=0, le=MAX_QUANTITY_PER_ITEM)


class ReplaceItemRequest(BaseModel):
    old_product_id: str
    new_product_id: str


class SetBudgetRequest(BaseModel):
    amount: float | None = Field(default=None, gt=0, le=100_000)
