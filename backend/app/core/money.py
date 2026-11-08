from decimal import ROUND_HALF_UP, Decimal

CURRENCY = "USD"
CENT = Decimal("0.01")


def money(value: Decimal | int | str | float) -> Decimal:
    """Quantize to cents. Floats go through str() so 49.99 stays exactly 49.99."""
    if isinstance(value, float):
        value = str(value)
    return Decimal(value).quantize(CENT, rounding=ROUND_HALF_UP)


def money_str(value: Decimal) -> str:
    """Format for PayPal's amount.value (e.g. '164.97')."""
    return str(money(value))
