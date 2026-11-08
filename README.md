# CartPilot AI

**Your personal AI shopping agent. From intent to checkout.**

Tell CartPilot what you need ("a home office setup under $500: keyboard, mouse, headphones, laptop stand"). The agent searches a real catalogue, compares options, builds a budget-optimised cart you can refine by chatting, and checks out through **PayPal Sandbox**. It shows success only after the backend has verified the capture with PayPal.

> Status: under active development for the hackathon (deadline 2026-11-12). See [docs/PLAN.md](docs/PLAN.md) for the milestone checklist. This README only describes features that are implemented.

## Stack

| Layer | Tech |
|---|---|
| Android | Kotlin, Jetpack Compose, Material 3, MVVM + repositories, StateFlow, Navigation Compose, Retrofit + kotlinx.serialization, Coil, Chrome Custom Tabs |
| Backend | Python 3.12, FastAPI, Pydantic v2, httpx |
| AI | Gemini API with function calling (behind a provider interface) |
| Payments | PayPal Orders API v2 (Sandbox) |

## Repository layout

```
android/   Android app (open this folder in Android Studio)
backend/   FastAPI backend
docs/      Plan, architecture, demo material
```

## Run the backend locally

Requires Python 3.12.

```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate    macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # then fill in keys (optional for the health check)
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

- Health: http://localhost:8000/api/v1/health
- Interactive API docs: http://localhost:8000/docs
- Tests: `python -m pytest -q`

### API (implemented so far)

All routes are under `/api/v1`. Errors use one shape: `{"error": {"code": "...", "message": "...", "details": ...}}`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check. Shows whether Gemini and PayPal are configured. |
| GET | `/products?q=&category=&min_price=&max_price=&min_rating=&in_stock=&sort=&limit=` | Search the catalogue. `sort` is one of `relevance`, `price_asc`, `price_desc`, `rating`. |
| GET | `/products/categories` | Categories with product counts |
| GET | `/products/compare?ids=kb-003,kb-005` | Compare 2–5 products |
| GET | `/products/{id}` | One product |
| GET / DELETE | `/cart/{session_id}` | Get or clear the cart |
| POST | `/cart/{session_id}/items` | `{"product_id", "quantity"}` |
| PATCH / DELETE | `/cart/{session_id}/items/{product_id}` | Set the quantity (`0` removes it) or remove the item |
| POST | `/cart/{session_id}/replace` | `{"old_product_id", "new_product_id"}` |
| PUT | `/cart/{session_id}/budget` | `{"amount": 500}` sets a budget; `null` clears it |
| GET | `/cart/{session_id}/budget-check?budget=` | Within-budget check plus cheaper-swap suggestions |
| POST | `/agent/chat` | `{"session_id", "message"}` → agent reply, product cards, actions taken, updated cart, budget status |
| DELETE | `/agent/sessions/{session_id}` | Reset the conversation (the cart is kept) |

### The AI agent

`POST /agent/chat` runs a tool-calling loop with Gemini, capped at 8 model steps per message. The model can only use these seven tools:

- `search_products`
- `compare_products`
- `add_to_cart`
- `remove_from_cart`
- `replace_product`
- `get_cart_summary`
- `validate_budget`

The backend validates every tool argument against a strict schema, and the session ID is bound on the server, so the model can't touch another user's cart. Prices and totals always come from the catalogue and cart services. There is **no payment tool**: payment happens only when the user taps Checkout and approves the payment in PayPal.

The LLM vendor sits behind `LLMProvider` (`backend/app/services/ai/provider.py`), so another provider can be added without changing the shopping workflow. If the primary Gemini model hits its rate limit, the backend automatically falls back to cheaper models.

The catalogue (`backend/seed/products.json`) is the only source of products and prices. The backend computes all totals with exact decimal arithmetic and ignores any prices a client sends. Product images are served from `/static/products/`; see [image credits](docs/IMAGE_CREDITS.md).

### Environment variables

| Variable | Purpose |
|---|---|
| `ENVIRONMENT` | `development` / `production` |
| `GEMINI_API_KEY` | Gemini API key ([AI Studio](https://aistudio.google.com/app/apikey)). The free tier allows only about 20 requests per day per model, so enable billing for demos. |
| `GEMINI_MODEL` | Primary model (default `gemini-3.5-flash`) |
| `GEMINI_FALLBACK_MODELS` | Comma-separated models to try when the primary is rate-limited (default `gemini-3.5-flash-lite,gemini-3.1-flash-lite`) |
| `AI_TIMEOUT_SECONDS` | Timeout per model request (default 60) |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | PayPal Sandbox REST app credentials |
| `PAYPAL_BASE_URL` | `https://api-m.sandbox.paypal.com` |
| `PUBLIC_BASE_URL` | Public HTTPS URL of the backend (for PayPal return/cancel) |
| `ANDROID_DEEP_LINK` | `cartpilot://payment` |

The app never sends secrets to Android. `/health` reports only whether each integration is configured.

## Run the Android app

Requirements: Android Studio (recent, bundles JDK 25) and Android SDK 36.

1. Open the `android/` folder in Android Studio and let Gradle sync.
2. Start the backend (above).
3. Run the `app` configuration on an emulator. The default backend URL is `http://10.0.2.2:8000/`, which is the host machine as seen from the emulator.
4. On a physical device, either run `adb reverse tcp:8000 tcp:8000` and add `cartpilot.apiBaseUrl=http://localhost:8000/` to `android/local.properties`, or point it at the deployed HTTPS backend.

Command line: `cd android && ./gradlew assembleDebug testDebugUnitTest`

## License

[MIT](LICENSE)
