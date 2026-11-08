"""Payment endpoints (PayPal Orders v2 Sandbox).

Flow: app -> POST /create-order -> opens approve_url in a Custom Tab -> PayPal redirects the browser to
GET /paypal/return (or /paypal/cancel) -> this page hands control back to the app via the
cartpilot://payment deep link -> app -> POST /capture-order -> backend captures + verifies.
If the deep link never fires, the app polls GET /orders/{order_id} when it resumes.
"""

import html
import re
from urllib.parse import quote

from fastapi import APIRouter, Depends, Path, Query, Request
from fastapi.responses import HTMLResponse

from app.api.deps import get_payment_service
from app.core.config import Settings, get_settings
from app.schemas.cart import SESSION_ID_PATTERN
from app.schemas.payment import (
    ORDER_ID_PATTERN,
    CaptureOrderRequest,
    CreateOrderRequest,
    CreateOrderResponse,
    OrderStatusResponse,
    PaymentReceipt,
)
from app.services.payments.payment_service import PaymentService

router = APIRouter(prefix="/payments", tags=["payments"])

_ORDER_ID_RE = re.compile(ORDER_ID_PATTERN)


def _return_base_url(request: Request, settings: Settings) -> str:
    # PUBLIC_BASE_URL (e.g. the Render HTTPS URL) wins; otherwise use the URL the app called us on,
    # which is reachable from the device's browser too (10.0.2.2 on the emulator, localhost via adb reverse).
    return settings.public_base_url or str(request.base_url)


@router.post("/create-order", response_model=CreateOrderResponse)
def create_order(
    body: CreateOrderRequest,
    request: Request,
    payments: PaymentService = Depends(get_payment_service),
    settings: Settings = Depends(get_settings),
) -> CreateOrderResponse:
    """Create a PayPal order for the session's cart. The amount comes from the server-side cart."""
    return payments.create_order(body.session_id, body.expected_total, body.user_confirmed, _return_base_url(request, settings))


@router.post("/capture-order", response_model=PaymentReceipt)
def capture_order(body: CaptureOrderRequest, payments: PaymentService = Depends(get_payment_service)) -> PaymentReceipt:
    """Capture an approved order and verify it with PayPal. Idempotent."""
    return payments.capture_order(body.session_id, body.order_id)


@router.get("/orders/{order_id}", response_model=OrderStatusResponse)
def order_status(
    order_id: str = Path(pattern=ORDER_ID_PATTERN),
    session_id: str = Query(pattern=SESSION_ID_PATTERN),
    payments: PaymentService = Depends(get_payment_service),
) -> OrderStatusResponse:
    """Current status from PayPal; lets the app recover if the deep link back never arrived."""
    return payments.order_status(session_id, order_id)


# ---------- browser redirect targets (PayPal return_url / cancel_url) ----------

@router.get("/paypal/return", response_class=HTMLResponse, include_in_schema=False)
def paypal_return(
    token: str = Query(default=""), settings: Settings = Depends(get_settings)
) -> HTMLResponse:
    # Nothing is captured or marked paid here; the app must call capture-order.
    return _handoff_page(settings, "return", token, "Payment approved", "Returning to CartPilot to confirm your order…")


@router.get("/paypal/cancel", response_class=HTMLResponse, include_in_schema=False)
def paypal_cancel(
    token: str = Query(default=""),
    settings: Settings = Depends(get_settings),
    payments: PaymentService = Depends(get_payment_service),
) -> HTMLResponse:
    if _ORDER_ID_RE.match(token):
        payments.mark_cancelled(token)
    return _handoff_page(settings, "cancel", token, "Payment cancelled", "No money was taken. Returning to CartPilot…")


def _handoff_page(settings: Settings, action: str, order_id: str, title: str, message: str) -> HTMLResponse:
    order_id = order_id if _ORDER_ID_RE.match(order_id) else ""
    query = f"?order_id={quote(order_id)}" if order_id else ""
    scheme, _, rest = settings.android_deep_link.partition("://")  # cartpilot, payment
    deep_link = f"{settings.android_deep_link}/{action}{query}"
    # Chrome on Android handles intent:// most reliably (also from Custom Tabs).
    intent_link = f"intent://{rest}/{action}{query}#Intent;scheme={scheme};package=com.cartpilot;end"
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>CartPilot · {html.escape(title)}</title>
<style>
  :root {{ color-scheme: light; }}
  body {{ margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:#F7F8F5;
         font-family: system-ui, -apple-system, Roboto, sans-serif; color:#252D29; }}
  .card {{ background:#fff; border:1px solid #E5EAE5; border-radius:20px; padding:32px 24px; margin:16px; max-width:360px; text-align:center; }}
  h1 {{ font-size:22px; margin:0 0 8px; }} p {{ color:#5E6963; margin:0 0 24px; line-height:1.5; }}
  a.btn {{ display:block; background:#075B43; color:#fff; text-decoration:none; padding:14px; border-radius:12px; font-weight:600; }}
  small {{ display:block; margin-top:16px; color:#5E6963; }}
</style></head>
<body><div class="card">
  <h1>{html.escape(title)}</h1>
  <p>{html.escape(message)}</p>
  <a class="btn" id="open" href="{html.escape(intent_link)}">Open CartPilot</a>
  <small>Not on Android? <a href="{html.escape(deep_link)}">Open the app link</a>. You can close this tab.</small>
</div>
<script>setTimeout(function () {{ window.location.href = {intent_link!r}; }}, 300);</script>
</body></html>"""
    return HTMLResponse(page, headers={"Cache-Control": "no-store"})
