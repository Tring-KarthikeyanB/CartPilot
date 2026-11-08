"""In-memory fake of the PayPal Orders v2 API, served through httpx.MockTransport."""

import base64
import itertools
import json

import httpx


class FakePayPal:
    def __init__(self, client_id: str = "cid", client_secret: str = "secret"):
        self.expected_auth = "Basic " + base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
        self.orders: dict[str, dict] = {}
        self.requests: list[httpx.Request] = []
        self.capture_request_ids: list[str] = []
        self.token_calls = 0
        self._ids = itertools.count(1)
        # Behaviour switches for failure tests
        self.decline_capture = False
        self.capture_amount_override: str | None = None
        self.capture_status = "COMPLETED"
        self.fail_create_with: int | None = None
        self.revoke_token_once = False

    # ---------- test helpers ----------

    def approve(self, order_id: str) -> None:
        self.orders[order_id]["status"] = "APPROVED"

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    # ---------- handler ----------

    def _handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/v1/oauth2/token":
            if request.headers.get("Authorization") != self.expected_auth:
                return httpx.Response(401, json={"error": "invalid_client", "error_description": "Client Authentication failed"})
            self.token_calls += 1
            return httpx.Response(200, json={"access_token": f"tok-{self.token_calls}", "token_type": "Bearer", "expires_in": 32400})

        if not request.headers.get("Authorization", "").startswith("Bearer tok-"):
            return httpx.Response(401, json={"name": "AUTHENTICATION_FAILURE", "message": "no token"})
        if self.revoke_token_once:
            self.revoke_token_once = False
            return httpx.Response(401, json={"name": "AUTHENTICATION_FAILURE", "message": "expired"})

        if path == "/v2/checkout/orders" and request.method == "POST":
            if self.fail_create_with:
                return httpx.Response(self.fail_create_with, json={"name": "INTERNAL_SERVER_ERROR", "message": "boom", "debug_id": "dbg"})
            body = json.loads(request.content)
            order_id = f"ORDER{next(self._ids):05d}TEST"
            self.orders[order_id] = {"body": body, "status": "PAYER_ACTION_REQUIRED", "captures": 0}
            return httpx.Response(200, json={
                "id": order_id, "status": "PAYER_ACTION_REQUIRED",
                "links": [
                    {"rel": "self", "href": f"https://api.sandbox.paypal.com/v2/checkout/orders/{order_id}"},
                    {"rel": "payer-action", "href": f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}"},
                ],
            })

        parts = path.strip("/").split("/")  # v2 checkout orders {id} [capture]
        order_id = parts[3] if len(parts) >= 4 else ""
        order = self.orders.get(order_id)
        if order is None:
            return httpx.Response(404, json={"name": "RESOURCE_NOT_FOUND", "details": [{"issue": "INVALID_RESOURCE_ID"}], "message": "not found"})

        if request.method == "GET":
            return httpx.Response(200, json=self._order_json(order_id))

        if path.endswith("/capture"):
            self.capture_request_ids.append(request.headers.get("PayPal-Request-Id", ""))
            if order["status"] == "COMPLETED":
                return httpx.Response(422, json={"name": "UNPROCESSABLE_ENTITY", "details": [{"issue": "ORDER_ALREADY_CAPTURED", "description": "already"}]})
            if order["status"] != "APPROVED":
                return httpx.Response(422, json={"name": "UNPROCESSABLE_ENTITY", "details": [{"issue": "ORDER_NOT_APPROVED", "description": "Payer has not yet approved"}]})
            if self.decline_capture:
                return httpx.Response(422, json={"name": "UNPROCESSABLE_ENTITY", "details": [{"issue": "INSTRUMENT_DECLINED", "description": "declined"}]})
            order["captures"] += 1
            order["status"] = "COMPLETED" if self.capture_status == "COMPLETED" else "APPROVED"
            return httpx.Response(201, json=self._order_json(order_id, capture=True))
        return httpx.Response(405)

    def _order_json(self, order_id: str, capture: bool = False) -> dict:
        order = self.orders[order_id]
        unit = order["body"]["purchase_units"][0]
        data = {"id": order_id, "status": order["status"], "purchase_units": [{"reference_id": "default", "custom_id": unit["custom_id"]}]}
        if order["captures"]:
            data["status"] = "COMPLETED" if self.capture_status == "COMPLETED" else order["status"]
            data["purchase_units"][0]["payments"] = {"captures": [{
                "id": f"CAP{order_id}", "status": self.capture_status,
                "amount": {"currency_code": unit["amount"]["currency_code"], "value": self.capture_amount_override or unit["amount"]["value"]},
            }]}
            data["payer"] = {"email_address": "buyer@example.com", "name": {"given_name": "Sandbox", "surname": "Buyer"}}
        return data
