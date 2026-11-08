"""Application settings loaded from environment variables (and an optional .env file).

Secrets (Gemini key, PayPal credentials) are never hardcoded; they come from the
environment only. See backend/.env.example for the full list.
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

APP_NAME = "CartPilot AI"
APP_VERSION = "0.1.0"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: str = "development"

    # AI provider
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.5-flash"

    # PayPal Sandbox
    paypal_client_id: str = ""
    paypal_client_secret: str = ""
    paypal_base_url: str = "https://api-m.sandbox.paypal.com"

    # Public HTTPS base URL of this backend (used for PayPal return/cancel URLs)
    public_base_url: str = "http://localhost:8000"
    # Android deep link that the backend redirects to after PayPal approval/cancel
    android_deep_link: str = "cartpilot://payment"

    @property
    def gemini_configured(self) -> bool:
        return bool(self.gemini_api_key)

    @property
    def paypal_configured(self) -> bool:
        return bool(self.paypal_client_id and self.paypal_client_secret)


@lru_cache
def get_settings() -> Settings:
    return Settings()
