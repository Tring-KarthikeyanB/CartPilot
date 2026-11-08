import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_cart_service, get_product_repository
from app.main import app
from app.repositories.product_repository import ProductRepository
from app.services.cart.cart_service import CartService
from app.services.products.catalog_service import CatalogService

SESSION = "test-session-0001"


@pytest.fixture(scope="session")
def repo() -> ProductRepository:
    return get_product_repository()


@pytest.fixture
def catalog(repo) -> CatalogService:
    return CatalogService(repo)


@pytest.fixture
def carts(repo) -> CartService:
    return CartService(repo)


@pytest.fixture
def client(carts):
    """API client with a fresh, isolated cart store per test."""
    app.dependency_overrides[get_cart_service] = lambda: carts
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
