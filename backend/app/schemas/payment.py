from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field

from app.schemas.cart import SESSION_ID_PATTERN

ORDER_ID_PATTERN = r"^[A-Z0-9]{5,40}$"


class PaymentStatus(str, Enum):
    awaiting_approval = "awaiting_approval"  # PayPal order created, buyer hasn't approved yet
    cancelled = "cancelled"  # buyer cancelled on PayPal (order can still be re-approved)
    completed = "completed"  # captured AND verified by the backend
    failed = "failed"  # capture returned but failed verification


class OrderItem(BaseModel):
    product_id: str
    name: str
    quantity: int
    unit_price: float
    line_total: float


class CreateOrderRequest(BaseModel):
    session_id: str = Field(pattern=SESSION_ID_PATTERN)
    # The total the user saw and approved on the checkout screen. Never used as the charge amount;
    # only compared with the server total so the user never approves a stale amount.
    expected_total: float = Field(gt=0)
    user_confirmed: bool = Field(description="Must be true: the user explicitly approved the payment in the app")


class CreateOrderResponse(BaseModel):
    order_id: str
    approve_url: str
    status: PaymentStatus
    amount: float
    currency: str
    items: list[OrderItem]


class CaptureOrderRequest(BaseModel):
    session_id: str = Field(pattern=SESSION_ID_PATTERN)
    order_id: str = Field(pattern=ORDER_ID_PATTERN)


class PaymentReceipt(BaseModel):
    order_id: str
    capture_id: str
    status: PaymentStatus
    paypal_status: str
    amount: float
    currency: str
    items: list[OrderItem]
    payer_email: str | None = None
    payer_name: str | None = None
    captured_at: datetime


class OrderStatusResponse(BaseModel):
    order_id: str
    status: PaymentStatus
    paypal_status: str | None  # CREATED / PAYER_ACTION_REQUIRED / APPROVED / COMPLETED / VOIDED ...
    can_capture: bool  # buyer approved on PayPal; the app should call capture-order
    approve_url: str | None
    amount: float
    currency: str
    receipt: PaymentReceipt | None = None
