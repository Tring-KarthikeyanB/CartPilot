from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.api import cart, health, products
from app.core.config import APP_NAME, APP_VERSION
from app.core.errors import register_error_handlers

API_PREFIX = "/api/v1"
STATIC_DIR = Path(__file__).resolve().parents[1] / "static"


def create_app() -> FastAPI:
    app = FastAPI(
        title=APP_NAME,
        version=APP_VERSION,
        description="AI shopping agent backend: catalogue, agent chat, cart and PayPal Sandbox checkout.",
    )
    register_error_handlers(app)
    app.include_router(health.router, prefix=API_PREFIX)
    app.include_router(products.router, prefix=API_PREFIX)
    app.include_router(cart.router, prefix=API_PREFIX)
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
    return app


app = create_app()
