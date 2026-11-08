"""Application settings loaded from environment variables (and an optional .env file).

Secrets (Gemini key, PayPal credentials) are never hardcoded; they come from the
environment only. See backend/.env.example for the full list.
"""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

APP_NAME = "CartPilot AI"
APP_VERSION = "0.1.0"


ENV_FILE = Path(__file__).resolve().parents[2] / ".env"  # backend/.env, regardless of the working directory


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ENV_FILE, env_file_encoding="utf-8", extra="ignore")

    environment: str = "development"

    # AI provider
    gemini_api_key: str = ""
    gemini_model: str = "gemini-3.5-flash"
    # Comma-separated models tried when the primary is rate limited / out of quota (quotas are per model).
    gemini_fallback_models: str = "gemini-3.5-flash-lite,gemini-3.1-flash-lite"
    ai_timeout_seconds: float = 60.0

    @property
    def gemini_fallback_model_list(self) -> list[str]:
        return [m.strip() for m in self.gemini_fallback_models.split(",") if m.strip()]

    # PayPal Sandbox
    paypal_client_id: str = ""
    paypal_client_secret: str = ""
    paypal_base_url: str = "https://api-m.sandbox.paypal.com"

    # Public HTTPS base URL of this backend (used for PayPal return/cancel URLs)
    # Leave empty to use the URL the app called (works for emulator/USB); set to the HTTPS URL when deployed.
    public_base_url: str = ""
    # Set automatically by Render (e.g. https://cartpilot-api.onrender.com)
    render_external_url: str = ""
    # Android deep link that the backend redirects to after PayPal approval/cancel
    android_deep_link: str = "cartpilot://payment"

    @property
    def effective_public_base_url(self) -> str:
        """Explicit PUBLIC_BASE_URL, else Render's URL, else '' (use the request's URL)."""
        return (self.public_base_url or self.render_external_url).rstrip("/")

    @property
    def gemini_configured(self) -> bool:
        return bool(self.gemini_api_key)

    @property
    def paypal_configured(self) -> bool:
        return bool(self.paypal_client_id and self.paypal_client_secret)


@lru_cache
def get_settings() -> Settings:
    return Settings()
