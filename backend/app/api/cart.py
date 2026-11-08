"""Direct cart operations for the app UI. The AI agent uses the same CartService through its tools."""

from fastapi import APIRouter, Depends, Path, Query, Request

from app.api.deps import absolute_url, get_cart_service
from app.schemas.cart import (
    SESSION_ID_PATTERN,
    AddItemRequest,
    BudgetCheck,
    CartSummary,
    ReplaceItemRequest,
    SetBudgetRequest,
    UpdateQuantityRequest,
)
from app.services.cart.cart_service import CartService

router = APIRouter(prefix="/cart", tags=["cart"])

SessionId = Path(pattern=SESSION_ID_PATTERN, description="Client-generated session id (8-64 chars)")


def present(summary: CartSummary, request: Request) -> CartSummary:
    return summary.model_copy(
        update={"items": [i.model_copy(update={"image_url": absolute_url(request, i.image_url)}) for i in summary.items]}
    )


@router.get("/{session_id}", response_model=CartSummary)
def get_cart(request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)) -> CartSummary:
    return present(carts.get_summary(session_id), request)


@router.delete("/{session_id}", response_model=CartSummary)
def clear_cart(request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)) -> CartSummary:
    return present(carts.clear(session_id), request)


@router.post("/{session_id}/items", response_model=CartSummary)
def add_item(
    body: AddItemRequest, request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)
) -> CartSummary:
    return present(carts.add_item(session_id, body.product_id, body.quantity), request)


@router.patch("/{session_id}/items/{product_id}", response_model=CartSummary)
def update_quantity(
    product_id: str,
    body: UpdateQuantityRequest,
    request: Request,
    session_id: str = SessionId,
    carts: CartService = Depends(get_cart_service),
) -> CartSummary:
    """Set the quantity of a cart line; 0 removes it."""
    return present(carts.set_quantity(session_id, product_id, body.quantity), request)


@router.delete("/{session_id}/items/{product_id}", response_model=CartSummary)
def remove_item(
    product_id: str, request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)
) -> CartSummary:
    return present(carts.remove_item(session_id, product_id), request)


@router.post("/{session_id}/replace", response_model=CartSummary)
def replace_item(
    body: ReplaceItemRequest, request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)
) -> CartSummary:
    return present(carts.replace_item(session_id, body.old_product_id, body.new_product_id), request)


@router.put("/{session_id}/budget", response_model=CartSummary)
def set_budget(
    body: SetBudgetRequest, request: Request, session_id: str = SessionId, carts: CartService = Depends(get_cart_service)
) -> CartSummary:
    """Set (or clear with amount=null) the session's budget."""
    return present(carts.set_budget(session_id, body.amount), request)


@router.get("/{session_id}/budget-check", response_model=BudgetCheck)
def budget_check(
    session_id: str = SessionId,
    budget: float | None = Query(default=None, gt=0, le=100_000, description="Override the stored budget"),
    carts: CartService = Depends(get_cart_service),
) -> BudgetCheck:
    return carts.check_budget(session_id, budget)
