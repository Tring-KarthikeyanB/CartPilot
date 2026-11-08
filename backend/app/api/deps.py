"""Process-wide singletons, exposed as FastAPI dependencies so tests can override them."""

from functools import lru_cache

from fastapi import Request

from app.repositories.product_repository import ProductRepository
from app.services.cart.cart_service import CartService
from app.services.products.catalog_service import CatalogService


@lru_cache
def get_product_repository() -> ProductRepository:
    return ProductRepository.from_json()


@lru_cache
def get_catalog_service() -> CatalogService:
    return CatalogService(get_product_repository())


@lru_cache
def get_cart_service() -> CartService:
    return CartService(get_product_repository())


def absolute_url(request: Request, path: str) -> str:
    """Turn a server path like /static/products/kb-001.jpg into a URL the caller can load.

    Uses the request's own base URL, so it works for the emulator (10.0.2.2), a USB-reversed
    device (localhost) and the HTTPS deployment (uvicorn --proxy-headers) alike.
    """
    if path.startswith(("http://", "https://")):
        return path
    return str(request.base_url).rstrip("/") + "/" + path.lstrip("/")
