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

| POST | `/payments/create-order` | `{"session_id", "expected_total", "user_confirmed": true}` → PayPal `order_id` and `approve_url` |
| POST | `/payments/capture-order` | `{"session_id", "order_id"}` → captures with PayPal and returns a verified receipt (idempotent) |
| GET | `/payments/orders/{order_id}?session_id=` | Status from PayPal (`can_capture` when the buyer has approved) |
| GET | `/payments/paypal/return`, `/payments/paypal/cancel` | Pages PayPal redirects the browser to; they hand control back to the app |

### PayPal Sandbox checkout

1. The app shows the order summary. The user explicitly approves, and the app sends `create-order` with the total they saw. The backend recomputes the total from its own cart and refuses if the two don't match. It then creates a PayPal Orders v2 order (`intent=CAPTURE`) for the **server-computed** amount.
2. The app opens `approve_url` in a Chrome Custom Tab. After approving or cancelling, PayPal redirects to the backend's `/payments/paypal/return` or `/payments/paypal/cancel`. That page sends the user back to the app via `intent://` or `cartpilot://payment/...`.
3. The app calls `capture-order`. The backend captures with PayPal and checks the order ID, the `COMPLETED` status, and that the captured amount and currency match the order. Only then does it return a receipt. A deep link on its own never counts as payment.
4. If the redirect never reaches the app (for example, the user closed the tab), the app checks `GET /payments/orders/{id}` when it resumes and captures if PayPal reports `APPROVED`.

**To test in Sandbox:** create a REST app at [developer.paypal.com](https://developer.paypal.com/dashboard/applications/sandbox) and put its client ID and secret in `backend/.env`. Then pay with the **personal** sandbox account under *Sandbox → Accounts* (log in with its email and system-generated password). No real money moves.

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
| `PUBLIC_BASE_URL` | Public HTTPS URL of the deployed backend, used for PayPal return/cancel. Leave it empty locally to use the URL the app called (e.g. `http://10.0.2.2:8000`). |
| `ANDROID_DEEP_LINK` | `cartpilot://payment` |

The app never sends secrets to Android. `/health` reports only whether each integration is configured.

## Run the Android app

Requirements: Android Studio (recent, bundles JDK 25) and Android SDK 36.

1. Open the `android/` folder in Android Studio and let Gradle sync.
2. Start the backend (above).
3. Run the `app` configuration on an emulator. The default backend URL is `http://10.0.2.2:8000/`, which is the host machine as seen from the emulator.
4. On a physical device, either run `adb reverse tcp:8000 tcp:8000` and add `cartpilot.apiBaseUrl=http://localhost:8000/` to `android/local.properties`, or point it at the deployed HTTPS backend.

Command line: `cd android && ./gradlew assembleDebug testDebugUnitTest`

### App screens

| Screen | What it does |
|---|---|
| **AI Shopping Home** | Chat with the agent. Includes suggested prompts, a typing indicator, product-card carousels grounded in the catalogue, a note of the actions the agent took ("Added …"), and a live cart summary. |
| **Product Discovery** | Search and filter by category, view product details, add to cart, and compare 2–3 products (cheapest, top rated, best value). |
| **Smart Cart** | Change quantities, remove items, see budget progress and over-budget warnings, apply one-tap cheaper swaps, or ask the AI to optimize the cart. |
| **Checkout** | Itemized summary, then an explicit approval checkbox, then PayPal in a Chrome Custom Tab. Handles cancelled, not-approved, declined and failed payments, and refuses to proceed if the cart total changed. |
| **Payment Success** | Order ID, PayPal capture ID, items and total. Shown only from a receipt the backend has verified. |

### Testing the full flow on a phone

1. Start the backend with `--host 0.0.0.0`.
2. Connect the phone over USB, run `adb reverse tcp:8000 tcp:8000`, and set `cartpilot.apiBaseUrl=http://localhost:8000/` in `android/local.properties`.
3. Run the app. Ask for a home office setup, open the cart, and check out.
4. In the Custom Tab, log in with your **sandbox personal** account and pay. The return page reopens CartPilot, which captures the payment and shows the receipt.

The API contract tests decode real backend responses stored in `app/src/test/resources/fixtures`. Regenerate them after changing the API with `cd backend && python scripts/export_api_fixtures.py`.

## License

[MIT](LICENSE)
