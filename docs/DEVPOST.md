# CartPilot AI: Devpost submission

**Tagline:** Your personal AI shopping agent. From intent to checkout.

## Inspiration

Online shopping for something like a home office setup means a dozen tabs: comparing keyboards, checking reviews, adding things up, realizing you're over budget, starting again. AI chatbots can *talk* about products, but they invent prices and stop short of buying. We wanted an agent that **does the shopping**: it understands the goal and budget, works only with real products and prices, builds the cart, and hands over to a payment step the user explicitly approves and can trust.

## What it does

- **Understands intent.** "I need a home office setup. My budget is $500: keyboard, mouse, headphones and laptop stand."
- **Shops for you.** The agent saves the budget, searches each category of a 37-product catalogue, picks well-rated items that fit, adds them to the cart, and explains why.
- **Refines in conversation.** "Replace my headphones with a cheaper option and keep it under $450." The agent swaps the item, saves the new limit and explains the savings.
- **Compares and browses.** Product discovery with search, categories and a side-by-side comparison (cheapest, top rated, best value).
- **Smart cart.** Live budget tracking, over-budget warnings, one-tap "swap and save" suggestions, and "Ask CartPilot to optimize".
- **Checks out with PayPal.** An explicit approval step, then the PayPal Sandbox checkout in a Chrome Custom Tab. The backend captures and **verifies** the payment before the app shows success.

## How we built it

- **Android:** Kotlin, Jetpack Compose and Material 3, MVVM with repositories and `StateFlow`, Navigation Compose, Retrofit with kotlinx.serialization, Coil, and Chrome Custom Tabs. Chat is the primary experience. A single shared cart state keeps chat, cart and checkout in sync.
- **Backend:** Python FastAPI with Pydantic v2 schemas. The catalogue is a static JSON seed (the single source of truth), with in-memory session carts and exact `Decimal` money.
- **AI agent:** Gemini function calling behind a provider-neutral `LLMProvider` interface, with seven tools: `search_products`, `compare_products`, `add_to_cart`, `remove_from_cart`, `replace_product`, `get_cart_summary` and `validate_budget`. Each turn is a bounded loop of up to 8 model steps, and every tool argument is validated by strict Pydantic models.
- **Payments:** the PayPal Orders v2 REST API (Sandbox), with OAuth token caching and idempotency keys:
  - **Create** uses the server-computed amount.
  - **Capture** happens only after buyer approval.
  - **Verification** checks the order ID, capture status, amount and currency.
- **Deployment:** a Render Blueprint (`render.yaml`) gives an HTTPS backend, and GitHub Actions CI runs the backend tests plus the Android build, unit tests and lint.

## Responsible agent design

- **Grounded:** products, prices, stock and IDs come only from the catalogue. Product cards are shown only for items the tools actually returned *and* the reply names.
- **Backend is authoritative:** totals are computed by the cart service. A client-supplied or model-supplied price is ignored or rejected.
- **The AI can't pay:** there is no payment tool. Paying requires the user to tick an approval box and approve again on PayPal.
- **Verified success:** a deep link back from PayPal is only a signal. The app shows "Payment successful" only after the backend captures the order with PayPal and checks the amount. Capture is idempotent, so retries can't double-charge.
- **Isolated sessions:** tools are bound server-side to the caller's session, and orders are scoped to their session.

## Challenges we ran into

- **Getting back from the browser.** A Custom Tab can't be forced to hand control back, and browsers can block custom-scheme redirects. We built three layers:
  - the return page tries an `intent://` deep link automatically
  - the same page shows an "Open CartPilot" button
  - the app checks the order's status when it resumes, so a payment is never lost and never double-captured
- **LLM quotas.** Gemini's free tier allows about 20 requests per day per model, and an agentic turn takes several requests. We cut the number of model steps per turn (batched parallel tool calls), honour the server's `retryDelay`, and fall back across models with per-model cooldowns. We checked that a conversation started on one model can continue on another.
- **Keeping the model honest.** The model once ignored a newly stated budget. An explicit budget rule in the prompt plus tool-level budget saving fixed it, and tests with a scripted fake model cover tool errors, invalid arguments and loop limits.

## Accomplishments we're proud of

- A complete, real flow from one sentence to a verified PayPal Sandbox capture, with no fake responses anywhere.
- **130+ automated tests:**
  - **Backend (106):** catalogue integrity, search and compare, cart maths, the agent loop with a scripted LLM, Gemini provider retries and fallback, and PayPal create, capture and verification against an in-memory fake PayPal.
  - **Android (24):** ViewModel tests for chat and every checkout path, plus API contract tests that decode real backend responses.
- JVM screenshot tests (Robolectric + Roborazzi) that render every screen from the real Compose code.

## What we learned

- With agents, the hard part isn't the prompt. It's **what the model is allowed to touch** and **who owns the numbers**.
- Payment UX on mobile is mostly about the unhappy paths: cancelled, closed tab, declined, pending, stale cart total.

## What's next for CartPilot AI

- A persistent database for carts and orders, plus user accounts.
- A real merchant catalogue and inventory feeds, with retrieval for larger catalogues.
- PayPal webhooks as an extra confirmation channel, plus refunds.
- Streaming agent responses and voice input.

## Built with

`kotlin` `jetpack-compose` `material-design` `android` `retrofit` `coil` `python` `fastapi` `pydantic` `google-gemini` `function-calling` `paypal` `paypal-orders-api` `render` `github-actions` `robolectric`

## Links

- **Repository:** https://github.com/Tring-KarthikeyanB/CartPilot
- **Demo video:** _add YouTube URL_
- **Live API:** https://cartpilot-api-kc77.onrender.com/docs
