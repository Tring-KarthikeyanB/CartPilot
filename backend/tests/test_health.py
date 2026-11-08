from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app


def _client(settings: Settings) -> TestClient:
    app.dependency_overrides[get_settings] = lambda: settings
    return TestClient(app)


def teardown_function() -> None:
    app.dependency_overrides.clear()


def test_health_ok_without_credentials():
    client = _client(Settings(_env_file=None, gemini_api_key="", paypal_client_id="", paypal_client_secret=""))
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "CartPilot AI"
    assert body["checks"] == {"gemini_configured": False, "paypal_configured": False}


def test_health_reports_configured_integrations_without_leaking_secrets():
    client = _client(
        Settings(_env_file=None, gemini_api_key="secret-gemini", paypal_client_id="id", paypal_client_secret="secret-pp")
    )
    response = client.get("/api/v1/health")

    assert response.json()["checks"] == {"gemini_configured": True, "paypal_configured": True}
    assert "secret" not in response.text


def test_unknown_route_returns_404():
    client = _client(Settings(_env_file=None))
    assert client.get("/api/v1/does-not-exist").status_code == 404


def test_root_points_to_health_and_docs():
    client = _client(Settings(_env_file=None))
    body = client.get("/").json()
    assert body["health"] == "/api/v1/health" and body["docs"] == "/docs"
