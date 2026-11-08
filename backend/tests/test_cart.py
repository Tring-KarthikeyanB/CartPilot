import pytest

from app.core.errors import ConflictError, InvalidInputError, NotFoundError
from tests.conftest import SESSION


# ---------- service ----------

def test_empty_cart(carts):
    s = carts.get_summary(SESSION)
    assert s.items == [] and s.subtotal == 0 and s.item_count == 0 and s.budget is None and not s.over_budget


def test_add_items_and_exact_totals(carts):
    carts.add_item(SESSION, "ms-001", 3)        # 3 x 19.99 = 59.97
    s = carts.add_item(SESSION, "kb-001")       # + 49.99
    assert s.subtotal == 109.96
    assert s.item_count == 4
    assert [(i.product_id, i.quantity, i.line_total) for i in s.items] == [("ms-001", 3, 59.97), ("kb-001", 1, 49.99)]


def test_adding_same_product_accumulates_with_cap(carts):
    carts.add_item(SESSION, "ms-001", 6)
    assert carts.add_item(SESSION, "ms-001", 4).items[0].quantity == 10
    with pytest.raises(InvalidInputError):
        carts.add_item(SESSION, "ms-001", 1)


def test_add_rejects_invalid_ids_quantities_and_out_of_stock(carts):
    with pytest.raises(NotFoundError):
        carts.add_item(SESSION, "xx-123")
    with pytest.raises(InvalidInputError):
        carts.add_item(SESSION, "kb-001", 0)
    with pytest.raises(InvalidInputError):
        carts.add_item(SESSION, "kb-001", 11)
    with pytest.raises(ConflictError):
        carts.add_item(SESSION, "ls-005")  # out of stock
    assert carts.get_summary(SESSION).items == []


def test_remove_item(carts):
    carts.add_item(SESSION, "kb-001")
    carts.add_item(SESSION, "ms-001")
    s = carts.remove_item(SESSION, "kb-001")
    assert [i.product_id for i in s.items] == ["ms-001"] and s.subtotal == 19.99
    with pytest.raises(NotFoundError):
        carts.remove_item(SESSION, "kb-001")  # no longer in cart
    with pytest.raises(NotFoundError):
        carts.remove_item(SESSION, "nope-1")


def test_set_quantity_and_zero_removes(carts):
    carts.add_item(SESSION, "hp-001")
    assert carts.set_quantity(SESSION, "hp-001", 2).subtotal == 79.98
    assert carts.set_quantity(SESSION, "hp-001", 0).items == []


def test_replace_keeps_position_and_quantity(carts):
    carts.add_item(SESSION, "kb-001")
    carts.add_item(SESSION, "hp-004", 2)
    carts.add_item(SESSION, "ms-001")
    s = carts.replace_item(SESSION, "hp-004", "hp-003")
    assert [(i.product_id, i.quantity) for i in s.items] == [("kb-001", 1), ("hp-003", 2), ("ms-001", 1)]
    assert s.subtotal == round(49.99 + 2 * 149.99 + 19.99, 2)


def test_replace_merges_when_new_product_already_in_cart(carts):
    carts.add_item(SESSION, "hp-003")
    carts.add_item(SESSION, "hp-005")
    s = carts.replace_item(SESSION, "hp-003", "hp-005")
    assert [(i.product_id, i.quantity) for i in s.items] == [("hp-005", 2)]


def test_replace_validation(carts):
    carts.add_item(SESSION, "wc-001")
    with pytest.raises(NotFoundError):
        carts.replace_item(SESSION, "wc-002", "wc-003")  # old not in cart
    with pytest.raises(NotFoundError):
        carts.replace_item(SESSION, "wc-001", "wc-999")  # new doesn't exist
    with pytest.raises(ConflictError):
        carts.replace_item(SESSION, "wc-001", "wc-004")  # new out of stock
    with pytest.raises(InvalidInputError):
        carts.replace_item(SESSION, "wc-001", "wc-001")
    assert [i.product_id for i in carts.get_summary(SESSION).items] == ["wc-001"]


def test_sessions_are_isolated(carts):
    carts.add_item(SESSION, "kb-001")
    assert carts.get_summary("another-session-2").items == []


def test_invalid_session_id(carts):
    with pytest.raises(InvalidInputError):
        carts.get_summary("bad id!")


# ---------- budget ----------

def _home_office(carts):
    for pid in ("kb-005", "ms-002", "hp-003", "ls-002"):  # 79.99 + 59.99 + 149.99 + 59.99 = 349.96
        carts.add_item(SESSION, pid)


def test_budget_within(carts):
    _home_office(carts)
    s = carts.set_budget(SESSION, 500)
    assert s.budget == 500 and s.budget_remaining == 150.04 and not s.over_budget
    check = carts.check_budget(SESSION)
    assert check.within_budget and check.remaining == 150.04 and check.overage == 0


def test_budget_over_with_cheaper_suggestions(carts):
    _home_office(carts)
    check = carts.check_budget(SESSION, 300)
    assert not check.within_budget and check.overage == 49.96
    assert "over" in check.message
    # Suggestions are real, cheaper, in-stock, same-category catalogue items.
    assert check.suggestions
    for s in check.suggestions:
        assert s.savings > 0 and s.with_rating >= 4.0
        assert carts.products.get(s.with_product_id).category == carts.products.get(s.replace_product_id).category
        assert carts.products.get(s.with_product_id).available
    assert [s.savings for s in check.suggestions] == sorted((s.savings for s in check.suggestions), reverse=True)


def test_budget_override_does_not_change_stored_budget(carts):
    carts.set_budget(SESSION, 1000)
    carts.check_budget(SESSION, 10)
    assert carts.get_summary(SESSION).budget == 1000


def test_budget_validation_and_clearing(carts):
    with pytest.raises(InvalidInputError):
        carts.set_budget(SESSION, 0)
    with pytest.raises(InvalidInputError):
        carts.set_budget(SESSION, -5)
    carts.set_budget(SESSION, 200)
    assert carts.set_budget(SESSION, None).budget is None
    assert carts.check_budget(SESSION).within_budget


# ---------- API ----------

BASE = f"/api/v1/cart/{SESSION}"


def test_cart_api_full_flow(client):
    assert client.get(BASE).json()["items"] == []

    r = client.post(f"{BASE}/items", json={"product_id": "kb-001", "quantity": 2})
    assert r.status_code == 200 and r.json()["subtotal"] == 99.98
    assert r.json()["items"][0]["image_url"].startswith("http://testserver/static/")

    client.post(f"{BASE}/items", json={"product_id": "hp-004"})
    r = client.post(f"{BASE}/replace", json={"old_product_id": "hp-004", "new_product_id": "hp-005"})
    assert [i["product_id"] for i in r.json()["items"]] == ["kb-001", "hp-005"]

    r = client.patch(f"{BASE}/items/kb-001", json={"quantity": 1})
    assert r.json()["subtotal"] == 119.98

    r = client.put(f"{BASE}/budget", json={"amount": 100})
    assert r.json()["over_budget"] is True and r.json()["budget_remaining"] == -19.98

    r = client.get(f"{BASE}/budget-check")
    assert r.json()["within_budget"] is False and r.json()["overage"] == 19.98

    r = client.delete(f"{BASE}/items/hp-005")
    assert r.json()["subtotal"] == 49.99

    assert client.delete(BASE).json()["items"] == []


def test_cart_api_errors(client):
    r = client.post(f"{BASE}/items", json={"product_id": "zz-999"})
    assert r.status_code == 404 and r.json()["error"]["code"] == "product_not_found"

    r = client.post(f"{BASE}/items", json={"product_id": "ls-005"})
    assert r.status_code == 409 and r.json()["error"]["code"] == "out_of_stock"

    r = client.post(f"{BASE}/items", json={"product_id": "kb-001", "quantity": 50})
    assert r.status_code == 422 and r.json()["error"]["code"] == "invalid_request"

    r = client.delete(f"{BASE}/items/kb-001")
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_in_cart"

    r = client.get("/api/v1/cart/short")
    assert r.status_code == 422

    r = client.put(f"{BASE}/budget", json={"amount": -1})
    assert r.status_code == 422


def test_client_cannot_set_prices(client):
    """Extra fields like a client-supplied price are ignored; totals come from the catalogue."""
    r = client.post(f"{BASE}/items", json={"product_id": "mn-004", "quantity": 1, "price": 0.01})
    assert r.json()["subtotal"] == 449.99
