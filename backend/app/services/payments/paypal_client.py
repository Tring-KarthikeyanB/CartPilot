"""Minimal PayPal REST client for Orders v2 (Sandbox or Live, chosen by base_url).

Only the backend talks to PayPal; the client secret never leaves the server.
Docs: https://developer.paypal.com/docs/api/orders/v2/
"""

import logging
import threading
import time
from typing import Any

import httpx

log = logging.getLogger(__name__)


class PayPalError(Exception):
    """A non-2xx PayPal response or a transport failure."""

    def __init__(self, status: int, message: str, *, name: str = "", issue: str = "", debug_id: str = ""):
        super().__init__(f"PayPal {status} {name} {issue}: {message}".strip())
        self.status = status
        self.name = name
        self.issue = issue  # e.g. ORDER_NOT_APPROVED, INSTRUMENT_DECLINED, ORDER_ALREADY_CAPTURED
        self.message = message
        self.debug_id = debug_id


class PayPalClient:
    def __init__(
        self,
        client_id: str,
        client_secret: str,
        base_url: str = "https://api-m.sandbox.paypal.com",
        timeout_seconds: float = 30.0,
        transport: httpx.BaseTransport | None = None,
    ):
        if not (client_id and client_secret):
            raise ValueError("PayPal client id/secret are required.")
        self._auth = (client_id, client_secret)
        self._http = httpx.Client(base_url=base_url, timeout=timeout_seconds, transport=transport)
        self._token: str | None = None
        self._token_expires_at = 0.0
        self._token_lock = threading.Lock()

    # ---------- auth ----------

    def _access_token(self, force_refresh: bool = False) -> str:
        with self._token_lock:
            if not force_refresh and self._token and time.monotonic() < self._token_expires_at:
                return self._token
            try:
                r = self._http.post("/v1/oauth2/token", auth=self._auth, data={"grant_type": "client_credentials"})
            except httpx.HTTPError as e:
                raise PayPalError(0, f"Could not reach PayPal: {e}") from e
            if r.status_code != 200:
                raise self._error(r)
            body = r.json()
            self._token = body["access_token"]
            # Refresh a minute early.
            self._token_expires_at = time.monotonic() + max(int(body.get("expires_in", 300)) - 60, 30)
            return self._token

    # ---------- requests ----------

    def _request(self, method: str, path: str, *, json: Any = None, request_id: str | None = None) -> dict[str, Any]:
        for attempt in (1, 2):
            headers = {
                "Authorization": f"Bearer {self._access_token(force_refresh=attempt == 2)}",
                "Content-Type": "application/json",
                "Prefer": "return=representation",
            }
            if request_id:
                headers["PayPal-Request-Id"] = request_id  # idempotency key
            try:
                r = self._http.request(method, path, json=json, headers=headers)
            except httpx.HTTPError as e:
                raise PayPalError(0, f"Could not reach PayPal: {e}") from e
            if r.status_code == 401 and attempt == 1:
                continue  # token revoked/expired early: refresh once
            if r.status_code >= 400:
                err = self._error(r)
                log.warning("PayPal %s %s -> %s %s (debug_id=%s)", method, path, err.status, err.issue or err.name, err.debug_id)
                raise err
            return r.json() if r.content else {}
        raise AssertionError("unreachable")

    @staticmethod
    def _error(r: httpx.Response) -> PayPalError:
        try:
            body = r.json()
        except ValueError:
            body = {}
        details = body.get("details") or [{}]
        return PayPalError(
            r.status_code,
            details[0].get("description") or body.get("message") or body.get("error_description") or r.text[:200],
            name=body.get("name") or body.get("error", ""),
            issue=details[0].get("issue", ""),
            debug_id=body.get("debug_id", ""),
        )

    # ---------- Orders v2 ----------

    def create_order(self, payload: dict[str, Any], request_id: str) -> dict[str, Any]:
        return self._request("POST", "/v2/checkout/orders", json=payload, request_id=request_id)

    def get_order(self, order_id: str) -> dict[str, Any]:
        return self._request("GET", f"/v2/checkout/orders/{order_id}")

    def capture_order(self, order_id: str, request_id: str) -> dict[str, Any]:
        return self._request("POST", f"/v2/checkout/orders/{order_id}/capture", json={}, request_id=request_id)

    def close(self) -> None:
        self._http.close()
