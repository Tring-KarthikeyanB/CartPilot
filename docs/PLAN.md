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

### M3 — AI agent ✅
- [x] Provider-neutral `LLMProvider` interface (`services/ai/provider.py`) + `GeminiProvider` (google-genai 3.0, manual function calling, thought signatures replayed verbatim)
- [x] Rate-limit handling: short 429s retried using the server's `retryDelay`; daily-quota 429s put the model in cooldown and fall back to the next model (`GEMINI_FALLBACK_MODELS`); distinct `ai_rate_limited` / `ai_quota_exhausted` errors
- [x] 7 tools with strict Pydantic argument validation (unknown fields rejected), session bound server-side, structured tool errors the model can recover from; no payment tool
- [x] Agent loop: ≤8 model steps/turn, ≤8 tool calls/step, every call answered in order, history trimmed at user boundaries, per-session lock, failed turns not committed
- [x] `POST /agent/chat` → reply, grounded product cards (only products surfaced by tools *and* named in the reply), actions, backend cart, budget; `DELETE /agent/sessions/{id}` resets conversation
- [x] 30 new tests (scripted fake provider plus stubbed Gemini SDK); 79 backend tests in total
- [x] Live test with real Gemini: home-office build ($500), cheaper swap with a new $450 budget, off-catalogue item (chair), price-override attempt, "ready to pay" points the user to Checkout

**Known constraint:** the Gemini free tier allows ~20 requests/day/model (5/min). One shopping turn uses 1–4 requests. The fallback chain stretches this, but **enable billing on the Google AI project before the demo**.

### M4 — PayPal Sandbox ✅ (live buyer approval pending, see below)
- [x] `PayPalClient` (httpx): OAuth client-credentials with a cached token, refresh on 401, `PayPal-Request-Id` idempotency keys, structured `PayPalError(issue, debug_id)`
- [x] `PaymentService`: the amount comes from the server cart; requires `user_confirmed=true`; `expected_total` must equal the server total (`cart_changed` 409); out-of-stock and empty-cart guards
- [x] Orders v2 `payment_source.paypal.experience_context` (PAY_NOW, NO_SHIPPING, return/cancel → backend); handles `payer-action` and `approve` links
- [x] Capture: verifies order id, `COMPLETED` status, completed capture, amount + currency equal the stored snapshot, and `custom_id`. Handles PENDING (`payment_pending`), not approved (409 + approve_url), declined (402), already captured (recovered via GET). Idempotent; only purchased quantities are removed from the cart
- [x] `POST /payments/create-order`, `POST /payments/capture-order`, `GET /payments/orders/{id}?session_id=` (resume fallback); `GET /payments/paypal/return|cancel` hand-off pages (`intent://` + `cartpilot://` link, never capture)
- [x] Orders are scoped to their session (another session gets 404); hostile `token` values are escaped
- [x] 25 tests against an in-memory fake PayPal (`tests/fake_paypal.py`); 104 backend tests in total
- [x] Live Sandbox: token, create ($119.97 from cart), status, stale-total rejection, capture-before-approval → `payment_not_approved`, cross-session 404, return page
- [ ] Live Sandbox capture after buyer approval (needs the sandbox buyer login, done manually)

**Android redirect design and its limits:** a Custom Tab can't be forced to return to the app. Chrome may also block an automatic jump to a custom scheme. So the return page tries `intent://…` automatically and shows an **Open CartPilot** button. When the app resumes with a pending order it calls `GET /payments/orders/{id}`: if PayPal says `APPROVED` it captures; otherwise it shows "not completed / reopen PayPal / cancel". Success is shown only from a verified capture response.

### M5 — Android screens ✅ (code complete; on-device run pending, see below)
- [x] Data layer: DTOs for every endpoint, domain models + mappers, repositories (Product/Cart/Agent/Payment/System), `safeApiCall` parses the backend error envelope into `AppResult.Failure(message, code, details)`, 120 s read timeout for agent turns
- [x] Shared cart state: `CartRepository.cart: StateFlow` updated by REST calls *and* agent replies (badge, cart and checkout always agree)
- [x] Session id + pending PayPal order persisted in SharedPreferences (`PrefsSessionStore`)
- [x] **Home / AI chat:** welcome, 4 suggested prompts, user/assistant bubbles, animated typing indicator, agent action ticks, grounded product-card carousel, cart summary pill, retryable error bubbles, new-conversation button, backend status in the top bar, product detail sheet with direct add
- [x] **Product discovery:** debounced search, category chips, adaptive grid with image/rating/price/stock, add to cart, detail sheet, compare mode (2–3 products) → comparison sheet with Cheapest / Top rated / Best value badges, unique and shared features
- [x] **Smart cart:** quantity stepper, remove, budget card (progress, remaining/over, warning), set/clear budget dialog, per-item "swap and save" suggestions (replace), "Ask CartPilot to optimize" (hands a prompt to the chat), checkout bar, empty/error/loading states
- [x] **Checkout:** itemized summary, budget note, explicit approval checkbox → Pay with PayPal (Chrome Custom Tab), states for creating / awaiting approval / capturing / cancelled / not completed / declined / failed with retry; `cart_changed` refreshes and requires re-approval
- [x] **Success:** animated confirmation, status, order ID, PayPal capture ID, payer, items, total; only from a backend-verified receipt
- [x] Deep links: `cartpilot://payment/{return|cancel}` (singleTask, `onNewIntent` and cold start) → `AppEvents` → NavHost routes to Checkout → backend capture. Resume fallback via order status; guarded against double capture
- [x] Tests: 6 Home + 11 Checkout ViewModel tests, 7 API contract tests decoding real backend responses (`backend/scripts/export_api_fixtures.py` → `app/src/test/resources/fixtures`). 24 Android tests; `assembleDebug` and `lintDebug` pass
- [ ] Run on a device/emulator against the live backend (this PC has no system image or emulator hypervisor driver)

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
