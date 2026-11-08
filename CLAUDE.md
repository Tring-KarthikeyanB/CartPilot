# CartPilot AI — project rules for Claude

Hackathon project: Android AI shopping agent (Kotlin/Compose) + FastAPI backend (Gemini function calling, PayPal Orders v2 Sandbox).
**Deadline: 2026-11-12. Solo dev. A working end-to-end demo beats extra features.**

Plan + milestone checklist: `docs/PLAN.md` (keep it updated as milestones complete).

## Repository layout

```
android/   Gradle project (open THIS folder in Android Studio). Single :app module, package com.cartpilot
backend/   FastAPI app. app/{api,core,schemas,services/{ai,cart,products,payments},repositories}, seed/products.json, tests/
docs/      PLAN.md, architecture, demo script, Devpost copy, screenshots
```

## Non-negotiable rules

- **Grounding:** the AI may only recommend products that exist in `backend/seed/products.json`. Product IDs, prices and stock always come from the catalogue repository, never from model output.
- **Totals are backend-computed.** Never trust AI- or client-supplied prices/totals/amounts. PayPal order amounts are derived from the server-side cart.
- **Every AI tool call is validated** on the backend (schema + existence of IDs + quantity bounds). Unknown tools are rejected. The AI has no SQL/shell/file access.
- **The AI cannot pay.** No agent tool creates or captures PayPal orders. Payment requires an explicit user tap on the Checkout screen.
- **Payment success is shown only after the backend captured and verified the order with PayPal** (status `COMPLETED`, amount and currency match the cart). A deep link opening is *not* proof of payment.
- **Secrets only via env vars** (`backend/.env`, never committed). No PayPal secret or Gemini key in the Android app.
- Don't fake API responses for the demo. Tests may use fakes/mocks; the app may not.

## Backend conventions

- Python 3.12, venv at `backend/.venv`. Run from `backend/`:
  - Install: `.venv/Scripts/python -m pip install -r requirements.txt`
  - Run: `.venv/Scripts/python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000`
  - Test: `.venv/Scripts/python -m pytest -q`
- All routes under `/api/v1`. Pydantic models in `app/schemas`. Routers in `app/api` stay thin; logic lives in `app/services`.
- Settings via `app.core.config.get_settings()` (pydantic-settings); inject with `Depends` so tests can override.
- LLM access goes through a provider interface in `app/services/ai/` (Gemini is one implementation) so the shopping workflow doesn't depend on a vendor SDK.
- Cart storage: in-memory, keyed by `session_id` (no database until the full flow works).
- Singletons (`ProductRepository`, `CatalogService`, `CartService`) come from `app/api/deps.py`; tests override `get_cart_service` for isolation (`tests/conftest.py`).
- Money: `Decimal` internally (`app/core/money.py`), serialized as JSON numbers; PayPal amounts use `money_str()`.
- Errors: raise `NotFoundError` / `ConflictError` / `InvalidInputError` (`app/core/errors.py`) with a stable `code`; never return ad-hoc error dicts.
- Product `image_url` is a `/static/...` path in the seed and is made absolute per request (`deps.absolute_url`). Deploy with `uvicorn --proxy-headers` so it's https behind Render.
- Pin dependency versions in `requirements.txt` (dev-only tools in `requirements-dev.txt`).
- AI: `services/ai/` = `provider.py` (neutral types) · `gemini_provider.py` · `tools.py` (specs + validating `ToolExecutor`) · `agent_service.py` (loop) · `prompts.py`. Agent tests use `ScriptedProvider` (tests/test_agent.py) — never hit the real API in pytest.
- Gemini free tier is ~20 req/day/model; don't burn quota on repeated live runs. `gemini-2.5-flash` is retired for new keys. The SDK is google-genai 3.x.
- Agent chat can take 10–40 s (tool steps and rate-limit waits), so Android must use a read timeout of at least 120 s for `/agent/chat`.
- Payments: `services/payments/paypal_client.py` (HTTP, no business logic) + `payment_service.py` (rules, verification, in-memory records keyed by PayPal order id). Tests use `tests/fake_paypal.py` (httpx MockTransport), never the real API. Real Sandbox create currently returns HTTP 200 + `PAYER_ACTION_REQUIRED` + `payer-action` link.
- **`backend/.env.example` must contain placeholders only** — real keys live in `backend/.env` (gitignored).

## Android conventions

- AGP 9.1.1 (built-in Kotlin, Kotlin 2.3.20), Gradle 9.5.0, JDK 25 (Android Studio JBR), compileSdk/targetSdk 36, minSdk 26.
- Build/test from `android/`: `./gradlew assembleDebug testDebugUnitTest`
- MVVM + repository: `ui/*` (Composables + ViewModel exposing `StateFlow<UiState>`) → `domain/repository` interfaces → `data/repository` impls → `data/remote` (Retrofit + kotlinx.serialization).
- No API calls in Composables. Screens are split into `XxxRoute` (gets ViewModel) and stateless `XxxScreen(state, callbacks)`.
- Manual DI via `core/AppContainer` (no Hilt/Koin). ViewModels expose a `Factory`.
- Network errors map through `core/safeApiCall` → `AppResult.Failure(message)`.
- Backend URL: `BuildConfig.API_BASE_URL` from `cartpilot.apiBaseUrl` (gradle.properties default `http://10.0.2.2:8000/`, override in `local.properties`). Cleartext allowed only for 10.0.2.2/localhost.
- Theme: `ui/theme/Theme.kt` — primary #075B43, background #F7F8F5, surface #FFF, text #252D29, border #E5EAE5, accent #F0A94B. Light theme, rounded cards, chat-first UX.
- PayPal: approval URL opened in Chrome Custom Tabs; backend return URL redirects to `cartpilot://payment/...` (intent filter on MainActivity, `singleTask`).
- App-wide events (`core/AppEvents`): PayPal redirects (held until CheckoutViewModel consumes them) and prompts handed to the chat. One-off UI effects use a `Channel` (see CheckoutViewModel).
- `CartRepository.cart` is the single shared cart `StateFlow`; anything returning a cart (incl. agent chat) must publish to it.
- Android tests: fakes in `src/test/.../Fakes.kt`; contract fixtures in `src/test/resources/fixtures` are generated by `backend/scripts/export_api_fixtures.py` — regenerate when backend schemas change. In coroutine tests, collect effects with `UnconfinedTestDispatcher` (advanceUntilIdle ignores backgroundScope).
- This dev PC cannot run the Android emulator (no system image or hypervisor driver); UI checks happen on the developer's device.

## Workflow

- One milestone at a time; run backend tests + Android build/tests after each; fix before moving on.
- Commit each working milestone. Update `docs/PLAN.md` checklist and README when behaviour changes.
- Don't claim a feature works unless it was run/tested.
