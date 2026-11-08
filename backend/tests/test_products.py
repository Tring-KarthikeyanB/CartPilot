from decimal import Decimal
from pathlib import Path

import pytest

from app.core.errors import InvalidInputError, NotFoundError
from app.schemas.product import Category, StockStatus
from app.services.products.catalog_service import SortOrder, parse_category

STATIC = Path(__file__).resolve().parents[1]


# ---------- catalogue integrity ----------

def test_catalogue_has_30_plus_products_across_all_categories(repo):
    products = repo.all()
    assert len(products) >= 30
    assert {p.category for p in products} == set(Category)


def test_every_product_is_complete_and_has_a_local_image(repo):
    for p in repo.all():
        assert p.currency == "USD"
        assert p.price > 0 and p.price == p.price.quantize(Decimal("0.01"))
        assert 0 <= p.rating <= 5
        assert p.name and p.description and p.features
        assert p.image_url.startswith("/static/products/")
        assert (STATIC / p.image_url.lstrip("/")).is_file(), f"missing image for {p.id}"


def test_catalogue_contains_out_of_stock_and_low_stock_items(repo):
    statuses = {p.stock_status for p in repo.all()}
    assert StockStatus.out_of_stock in statuses and StockStatus.low_stock in statuses


def test_prices_parse_exactly(repo):
    assert repo.get("kb-001").price == Decimal("49.99")


# ---------- search ----------

def test_search_infers_category_from_query(catalog):
    results = catalog.search("wireless keyboard")
    assert results and all(p.category == Category.keyboards for p in results)
    assert "wireless" in results[0].features


def test_search_understands_synonyms(catalog):
    assert all(p.category == Category.headphones for p in catalog.search("earbuds"))
    assert {p.id for p in catalog.search("noise cancelling headphones")[:3]} >= {"hp-003", "hp-004"}
    # "monitor arm" is a desk accessory, not a monitor
    assert catalog.search("monitor arm")[0].id == "da-002"


def test_search_ranks_by_specific_words_within_category(catalog):
    assert catalog.search("desk lamp")[0].id == "da-001"
    assert catalog.search("ergonomic mouse")[0].id == "ms-002"
    # "headphones" should rank over-ear headphones above earbuds
    assert catalog.search("headphones")[0].id != "hp-006"


def test_search_price_words_prefer_cheaper_matches(catalog):
    results = catalog.search("cheap headphones with mic")
    assert all("microphone" in p.features for p in results[:3])
    assert results[0].price <= results[1].price


def test_search_price_filter_and_sort(catalog):
    results = catalog.search(category="headphones", max_price=100, sort=SortOrder.price_asc)
    prices = [p.price for p in results]
    assert prices == sorted(prices) and max(prices) <= 100
    assert "hp-004" not in {p.id for p in results}


def test_search_in_stock_only_excludes_out_of_stock(catalog):
    ids = {p.id for p in catalog.search(category="laptop_stands", in_stock_only=True)}
    assert "ls-005" not in ids and ids


def test_search_unrelated_query_returns_nothing(catalog):
    assert catalog.search("refrigerator") == []


def test_search_rejects_bad_ranges_and_categories(catalog):
    with pytest.raises(InvalidInputError):
        catalog.search(min_price=200, max_price=100)
    with pytest.raises(InvalidInputError):
        catalog.search(category="toasters")


@pytest.mark.parametrize(
    "raw,expected",
    [("Keyboards", Category.keyboards), ("laptop stand", Category.laptop_stands), ("laptop-stands", Category.laptop_stands),
     ("Desk accessories", Category.desk_accessories), ("mouse", Category.mice), ("webcam", Category.webcams)],
)
def test_parse_category_aliases(raw, expected):
    assert parse_category(raw) == expected


# ---------- comparison ----------

def test_compare_identifies_cheapest_top_rated_and_value(catalog):
    result = catalog.compare(["kb-003", "kb-005", "kb-006"])
    assert result.cheapest_id == "kb-005"
    assert result.highest_rated_id == "kb-003"
    assert result.best_value_id in {"kb-003", "kb-005", "kb-006"}
    assert "mechanical" in result.shared_features
    assert result.price_range == (79.99, 129.99)
    assert "cheapest" in result.summary


def test_compare_mentions_out_of_stock(catalog):
    assert "out of stock" in catalog.compare(["wc-002", "wc-004"]).summary


def test_compare_validation(catalog):
    with pytest.raises(InvalidInputError):
        catalog.compare(["kb-001"])
    with pytest.raises(InvalidInputError):
        catalog.compare(["kb-001", "kb-001"])  # duplicates collapse to one
    with pytest.raises(NotFoundError):
        catalog.compare(["kb-001", "kb-999"])


# ---------- API ----------

def test_list_products_api(client):
    r = client.get("/api/v1/products")
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == len(body["items"]) >= 30
    first = body["items"][0]
    assert first["image_url"].startswith("http://testserver/static/products/")
    assert isinstance(first["price"], float)


def test_list_products_with_filters_api(client):
    r = client.get("/api/v1/products", params={"q": "headphones", "max_price": 100, "sort": "price_asc"})
    items = r.json()["items"]
    assert items and all(i["category"] == "headphones" and i["price"] <= 100 for i in items)


def test_product_image_is_served(client):
    url = client.get("/api/v1/products/kb-001").json()["image_url"]
    r = client.get(url.replace("http://testserver", ""))
    assert r.status_code == 200 and r.headers["content-type"] == "image/jpeg"


def test_get_unknown_product_returns_404_envelope(client):
    r = client.get("/api/v1/products/kb-999")
    assert r.status_code == 404
    assert r.json() == {"error": {"code": "product_not_found", "message": "Product 'kb-999' does not exist in the catalogue."}}


def test_invalid_category_returns_422(client):
    r = client.get("/api/v1/products", params={"category": "toasters"})
    assert r.status_code == 422 and r.json()["error"]["code"] == "invalid_category"


def test_invalid_query_param_returns_validation_envelope(client):
    r = client.get("/api/v1/products", params={"max_price": "cheap"})
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "invalid_request"


def test_compare_api(client):
    r = client.get("/api/v1/products/compare", params={"ids": "hp-003,hp-004,hp-005"})
    assert r.status_code == 200
    body = r.json()
    assert body["cheapest_id"] == "hp-005" and body["highest_rated_id"] == "hp-004"
    assert body["products"][0]["product"]["image_url"].startswith("http://testserver/")


def test_compare_api_unknown_id(client):
    r = client.get("/api/v1/products/compare", params={"ids": "hp-003,zz-000"})
    assert r.status_code == 404


def test_categories_api(client):
    cats = client.get("/api/v1/products/categories").json()
    assert len(cats) == 7 and all(c["product_count"] >= 4 for c in cats)
