from fastapi import FastAPI

from app.api import health
from app.core.config import APP_NAME, APP_VERSION

API_PREFIX = "/api/v1"


def create_app() -> FastAPI:
    app = FastAPI(
        title=APP_NAME,
        version=APP_VERSION,
        description="AI shopping agent backend: catalogue, agent chat, cart and PayPal Sandbox checkout.",
    )
    app.include_router(health.router, prefix=API_PREFIX)
    return app


app = create_app()
