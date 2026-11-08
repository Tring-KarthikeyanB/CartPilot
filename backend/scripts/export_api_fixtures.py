"""Record real backend JSON responses as fixtures for the Android contract tests.

Uses the in-process app with a scripted LLM and the fake PayPal (no network), so the shapes are exactly
what the API returns. Run from backend/:  python scripts/export_api_fixtures.py
Output: android/app/src/test/resources/fixtures/*.json
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402

from app.api.deps import get_agent_service, get_cart_service, get_payment_service, get_product_repository  # noqa: E402
from app.core.config import Settings, get_settings  # noqa: E402
from app.main import app  # noqa: E402
from app.services.ai.agent_service import AgentService  # noqa: E402
from app.services.cart.cart_service import CartService  # noqa: E402
from app.services.payments.payment_service import PaymentService  # noqa: E402
from app.services.payments.paypal_client import PayPalClient  # noqa: E402
from app.services.products.catalog_service import CatalogService  # noqa: E402
from tests.fake_paypal import FakePayPal  # noqa: E402
from tests.test_agent import ScriptedProvider, calls, say  # noqa: E402

OUT = ROOT.parent / "android" / "app" / "src" / "test" / "resources" / "fixtures"
SID = "fixture-session-01"


def main() -> None:
    repo = get_product_repository()
    carts = CartService(repo)
    paypal = FakePayPal()
    payments = PaymentService(PayPalClient("cid", "secret", transport=paypal.transport()), carts)
    provider = ScriptedProvider([
        calls(("validate_budget", {"budget": 500}), ("search_products", {"category": "headphones", "max_price": 200})),
        calls(("add_to_cart", {"product_id": "hp-003"}), ("add_to_cart", {"product_id": "kb-005"})),
        say("Added Halo ANC Wireless Headphones ($149.99) and Keyline K84 Compact Wireless Mechanical Keyboard ($79.99)."),
    ])
    app.dependency_overrides[get_cart_service] = lambda: carts
    app.dependency_overrides[get_payment_service] = lambda: payments
    app.dependency_overrides[get_agent_service] = lambda: AgentService(provider, CatalogService(repo), carts)
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, gemini_api_key="x", paypal_client_id="x", paypal_client_secret="x")

    OUT.mkdir(parents=True, exist_ok=True)
    c = TestClient(app, base_url="http://10.0.2.2:8000")

    def save(name: str, response) -> dict:
        body = response.json()
        (OUT / f"{name}.json").write_text(json.dumps(body, indent=2), encoding="utf-8")
        print(f"{name}: {response.status_code}")
        return body

    save("health", c.get("/api/v1/health"))
    save("products", c.get("/api/v1/products", params={"q": "headphones"}))
    save("categories", c.get("/api/v1/products/categories"))
    save("compare", c.get("/api/v1/products/compare", params={"ids": "kb-003,kb-005,kb-006"}))
    save("chat", c.post("/api/v1/agent/chat", json={"session_id": SID, "message": "headphones and keyboard, $500"}))
    save("cart", c.put(f"/api/v1/cart/{SID}/budget", json={"amount": 200}))
    save("budget_check", c.get(f"/api/v1/cart/{SID}/budget-check"))
    save("error_not_found", c.get("/api/v1/products/zz-000"))
    save("error_validation", c.post(f"/api/v1/cart/{SID}/items", json={"product_id": "kb-001", "quantity": 99}))
    save("error_cart_changed", c.post("/api/v1/payments/create-order", json={"session_id": SID, "expected_total": 1, "user_confirmed": True}))
    order = save("create_order", c.post("/api/v1/payments/create-order", json={"session_id": SID, "expected_total": 229.98, "user_confirmed": True}))
    save("error_not_approved", c.post("/api/v1/payments/capture-order", json={"session_id": SID, "order_id": order["order_id"]}))
    save("order_status_pending", c.get(f"/api/v1/payments/orders/{order['order_id']}", params={"session_id": SID}))
    paypal.approve(order["order_id"])
    save("receipt", c.post("/api/v1/payments/capture-order", json={"session_id": SID, "order_id": order["order_id"]}))
    save("order_status_completed", c.get(f"/api/v1/payments/orders/{order['order_id']}", params={"session_id": SID}))
    app.dependency_overrides.clear()


if __name__ == "__main__":
    main()
