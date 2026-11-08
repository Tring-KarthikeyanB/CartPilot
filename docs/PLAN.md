# CartPilot AI — Implementation plan

Deadline **2026-11-12** (4 days from 2026-11-08). Priority: complete chat → cart → PayPal Sandbox → verified success journey, then polish and submission material.

## Architecture

```
┌──────────────── Android (Kotlin, Compose, MVVM) ────────────────┐
│ Home/Chat ─ Products ─ Cart ─ Checkout ─ Success                 │
│   ViewModels (StateFlow) → Repositories → Retrofit (HTTPS/JSON)  │
│   Chrome Custom Tab ⇄ PayPal approval; deep link cartpilot://    │
└───────────────────────────────┬──────────────────────────────────┘
                                │ /api/v1
┌───────────────────────────────▼──────────────────────────────────┐
│ FastAPI                                                           │
│  api/ (thin routers) → services/                                  │
│   ai/        AgentService loop + LLMProvider interface            │
│              └ GeminiProvider (function calling)                  │
│   products/  catalogue search/compare (seed/products.json)        │
│   cart/      in-memory session carts, authoritative totals        │
│   payments/  PayPal Orders v2 client (OAuth, create, capture)     │
│  Tool calls validated → only catalogue-backed cart mutations      │
└───────────────┬───────────────────────────────┬──────────────────┘
                │                               │
        Gemini API (google-genai)       PayPal Sandbox REST API
```

### Payment flow (and why)

1. User taps **Pay with PayPal** (explicit approval) → `POST /payments/create-order {session_id}`.
2. Backend computes total from the server cart, creates the PayPal order (`intent=CAPTURE`, `return_url`/`cancel_url` = backend HTTPS endpoints), stores `order_id → session/cart snapshot/amount`, returns `approve_url`.
3. Android opens `approve_url` in a Chrome Custom Tab.
4. PayPal redirects to `GET /payments/paypal/return?token=<order_id>` (or `/cancel`). Backend responds `302 cartpilot://payment/return?order_id=…` (or `/cancel`).
5. Android receives the deep link → `POST /payments/capture-order {order_id}`.
6. Backend captures with PayPal, verifies `status == COMPLETED` and captured amount/currency equal the stored snapshot, clears the cart, returns the receipt. Only then does Android show Success.

**Limitation & mitigation:** Custom Tabs can't be forced to hand control back; a user may close the tab, or a browser may block the custom-scheme redirect. Mitigation: when the app resumes with a pending order, it calls `GET /payments/orders/{order_id}` (backend asks PayPal for the order status) — `APPROVED` → capture, `CREATED`/`PAYER_ACTION_REQUIRED` → "Payment not completed, reopen PayPal / cancel". The backend return page also renders an HTML fallback with an "Open CartPilot" button in case the automatic redirect is blocked. Payment is never assumed from the redirect alone.

PayPal return URLs need a public URL; for local testing `http://localhost:8000` works in the emulator browser only via `10.0.2.2`, so the real flow is tested against the Render HTTPS deployment (or an `ngrok`/`cloudflared` tunnel).

## Milestones & checklist

### M1 — Foundations ✅
- [x] Inspect workspace; install Python 3.12 (winget, user scope)
- [x] Root structure, CLAUDE.md, plan, README, MIT license, .gitignore
- [x] FastAPI app factory, settings (env vars), `GET /api/v1/health` + tests
- [x] Android project (AGP 9.1.1 / Gradle 9.5.0 / Compose M3), theme, Retrofit, manual DI, Home screen showing backend health, ViewModel unit tests
- [x] Backend run locally + health verified with curl; Android `assembleDebug` + unit tests

### M2 — Catalogue & cart (backend) ✅
- [x] `seed/products.json`: 37 products across 7 categories (fictional brands, USD), including low-stock and out-of-stock items
- [x] Self-hosted product images in `static/products/` (built by `scripts/build_product_images.py`, credits in `docs/IMAGE_CREDITS.md`)
- [x] ProductRepository (validated on load) + CatalogService: search (category inference from text, synonyms, price/rating/stock filters, sort), compare (cheapest / top rated / best value, shared and unique features)
- [x] `GET /products`, `/products/categories`, `/products/compare?ids=`, `/products/{id}` (404 envelope)
- [x] CartService: in-memory session carts, add/set-quantity/remove/replace/clear, Decimal totals, budget plus cheaper-swap suggestions
- [x] Cart endpoints: `GET/DELETE /cart/{sid}`, `POST /cart/{sid}/items`, `PATCH|DELETE /cart/{sid}/items/{pid}`, `POST /cart/{sid}/replace`, `PUT /cart/{sid}/budget`, `GET /cart/{sid}/budget-check`
- [x] Unified error envelope `{"error":{"code","message","details?"}}`
- [x] 51 tests: catalogue integrity, search, compare, budget, totals, add/remove/replace, invalid IDs/quantities/sessions, out of stock, API errors, client-supplied prices ignored

### M3 — AI agent
- [ ] `LLMProvider` interface + `GeminiProvider` (google-genai, function calling)
- [ ] 7 tools: search_products, compare_products, add_to_cart, remove_from_cart, replace_product, get_cart_summary, validate_budget — argument validation, bounded loop
- [ ] `POST /agent/chat {session_id, message}` → reply text, products referenced, cart snapshot, budget
- [ ] Tests with a scripted fake provider (tool loop, invalid tool args, unknown product IDs)
- [ ] Manual test with real Gemini key

### M4 — PayPal Sandbox
- [ ] PayPal client: OAuth token cache, create order, get order, capture
- [ ] `POST /payments/create-order`, `POST /payments/capture-order`, `GET /payments/orders/{id}`, return/cancel redirect endpoints
- [ ] Amount/currency verification, idempotent capture, cancel handling
- [ ] Tests with mocked PayPal HTTP (httpx MockTransport): create, capture verify, mismatch, cancel, PayPal errors
- [ ] Manual Sandbox test with sandbox buyer account

### M5 — Android screens
- [ ] Chat home (bubbles, suggested prompts, typing indicator, inline product cards)
- [ ] Product discovery (grid, compare, add to cart, availability)
- [ ] Smart cart (qty, remove/replace, budget bar & warning, AI suggestions)
- [ ] Checkout (summary, explicit approval, Custom Tab, loading/error/cancel)
- [ ] Success (order ID, capture ID, items, total)
- [ ] Deep-link handling + resume fallback
- [ ] ViewModel unit tests

### M6 — Deploy & end-to-end
- [ ] Render deployment (render.yaml), env vars, HTTPS public URL
- [ ] Release-config Android build pointing at Render
- [ ] Full E2E on emulator/device with Sandbox buyer

### M7 — Submission
- [ ] README (setup, env vars, sandbox testing), architecture diagram, screenshots
- [ ] Demo script (<3 min), Devpost description
- [ ] Public GitHub repo push; YouTube video (manual)

## Credentials needed from the developer
- Gemini API key (M3) — https://aistudio.google.com/app/apikey
- PayPal Sandbox REST app client ID + secret, and a sandbox **personal** (buyer) account (M4) — https://developer.paypal.com/dashboard/
- Render account + GitHub repo (M6)
