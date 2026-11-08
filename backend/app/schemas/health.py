from datetime import datetime

from pydantic import BaseModel


class HealthChecks(BaseModel):
    gemini_configured: bool
    paypal_configured: bool


class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    environment: str
    timestamp: datetime
    checks: HealthChecks
