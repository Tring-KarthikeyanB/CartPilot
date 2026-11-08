from pydantic import BaseModel, Field

from app.schemas.cart import SESSION_ID_PATTERN, BudgetCheck, CartSummary
from app.schemas.product import Product


class ChatRequest(BaseModel):
    session_id: str = Field(pattern=SESSION_ID_PATTERN)
    message: str = Field(min_length=1, max_length=2000)


class AgentAction(BaseModel):
    tool: str
    ok: bool
    summary: str


class ChatResponse(BaseModel):
    session_id: str
    reply: str
    products: list[Product]  # catalogue products the reply refers to (for product cards)
    actions: list[AgentAction]  # what the agent did this turn
    cart: CartSummary  # authoritative, backend-computed cart after this turn
    cart_updated: bool
    budget: BudgetCheck | None
