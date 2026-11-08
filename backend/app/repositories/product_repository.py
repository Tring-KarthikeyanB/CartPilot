"""Read-only product catalogue loaded from seed/products.json (the single source of truth for products)."""

import json
from pathlib import Path

from pydantic import TypeAdapter

from app.core.errors import NotFoundError
from app.schemas.product import Product

DEFAULT_SEED_PATH = Path(__file__).resolve().parents[2] / "seed" / "products.json"


class ProductRepository:
    def __init__(self, products: list[Product]):
        ids = [p.id for p in products]
        duplicates = {i for i in ids if ids.count(i) > 1}
        if duplicates:
            raise ValueError(f"Duplicate product IDs in catalogue: {sorted(duplicates)}")
        self._by_id: dict[str, Product] = {p.id: p for p in products}

    @classmethod
    def from_json(cls, path: Path = DEFAULT_SEED_PATH) -> "ProductRepository":
        raw = json.loads(path.read_text(encoding="utf-8"))
        return cls(TypeAdapter(list[Product]).validate_python(raw))

    def all(self) -> list[Product]:
        return list(self._by_id.values())

    def find(self, product_id: str) -> Product | None:
        return self._by_id.get(product_id.strip().lower()) if product_id else None

    def get(self, product_id: str) -> Product:
        product = self.find(product_id)
        if product is None:
            raise NotFoundError(f"Product '{product_id}' does not exist in the catalogue.", code="product_not_found")
        return product
