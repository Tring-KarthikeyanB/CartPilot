from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from app.core.config import APP_NAME, APP_VERSION, Settings, get_settings
from app.schemas.health import HealthChecks, HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
def health(settings: Settings = Depends(get_settings)) -> HealthResponse:
    """Liveness check. Reports whether external integrations are configured (never the secrets)."""
    return HealthResponse(
        status="ok",
        service=APP_NAME,
        version=APP_VERSION,
        environment=settings.environment,
        timestamp=datetime.now(timezone.utc),
        checks=HealthChecks(
            gemini_configured=settings.gemini_configured,
            paypal_configured=settings.paypal_configured,
        ),
    )
