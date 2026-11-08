import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.api import agent, cart, health, payments, products
from app.core.config import APP_NAME, APP_VERSION
from app.core.errors import register_error_handlers

API_PREFIX = "/api/v1"
STATIC_DIR = Path(__file__).resolve().parents[1] / "static"

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


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
    app.include_router(agent.router, prefix=API_PREFIX)
    app.include_router(payments.router, prefix=API_PREFIX)
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.get("/", include_in_schema=False)
    def root() -> dict:
        return {"service": APP_NAME, "version": APP_VERSION, "health": f"{API_PREFIX}/health", "docs": "/docs"}

    return app


app = create_app()
