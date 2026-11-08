# CartPilot AI: architecture

## System overview

```mermaid
flowchart LR
    subgraph Android["Android app (Kotlin · Jetpack Compose · MVVM)"]
        UI["Screens<br/>Chat · Discover · Cart · Checkout · Success"]
        VM["ViewModels<br/>StateFlow UI state"]
        Repo["Repositories<br/>shared cart StateFlow"]
        API["Retrofit + kotlinx.serialization"]
        CT["Chrome Custom Tab"]
        UI --> VM --> Repo --> API
        VM -. "approve_url" .-> CT
    end

    subgraph Backend["FastAPI backend (Render, HTTPS)"]
        R["/api/v1 routers"]
        Agent["AgentService<br/>bounded tool loop"]
        Tools["ToolExecutor<br/>7 validated tools"]
        Catalog["CatalogService<br/>search · compare"]
        Cart["CartService<br/>Decimal totals · budget"]
        Pay["PaymentService<br/>create · capture · verify"]
        Seed[("seed/products.json<br/>37 products")]
        R --> Agent --> Tools
        Tools --> Catalog
        Tools --> Cart
        R --> Catalog
        R --> Cart
        R --> Pay --> Cart
        Catalog --> Seed
    end

    API -- "HTTPS JSON" --> R
    Agent -- "LLMProvider interface" --> Gemini["Gemini API<br/>function calling"]
    Pay -- "Orders v2 REST" --> PayPal["PayPal Sandbox"]
    CT -- "buyer approves" --> PayPal
    PayPal -- "return_url" --> R
    R -- "cartpilot://payment/return" --> UI
```

## One agent turn

```mermaid
sequenceDiagram
    autonumber
    participant App as Android chat
    participant API as POST /agent/chat
    participant Agent as AgentService
    participant LLM as Gemini
    participant Tools as ToolExecutor
    participant Cart as CartService (catalogue prices)

    App->>API: {session_id, "Home office under $500…"}
    API->>Agent: chat(session, message)
    loop at most 8 model steps
        Agent->>LLM: system prompt + history + 7 tool specs
        LLM-->>Agent: function calls (e.g. validate_budget, 4× search_products)
        Agent->>Tools: execute each call (strict Pydantic args, session bound)
        Tools->>Cart: add_to_cart / replace_product …
        Cart-->>Tools: cart with backend-computed subtotal
        Tools-->>Agent: results or structured errors
        Agent->>LLM: function responses (paired, in order)
    end
    LLM-->>Agent: final text (no more tool calls)
    Agent-->>API: reply + grounded product cards + actions + cart + budget
    API-->>App: ChatResponse
```

Guardrails:

- **Seven tools only:** `search_products`, `compare_products`, `add_to_cart`, `remove_from_cart`, `replace_product`, `get_cart_summary`, `validate_budget`. No payment, SQL, shell or network tool exists.
- **Strict arguments:** extra fields such as a model-supplied `price` are rejected. Product IDs must exist, quantities must be 1–10, and out-of-stock items can't be added.
- **Grounded product cards:** the app only gets cards for catalogue products that a tool returned this turn *and* that the reply names.
- **Backend-computed totals:** the model reads subtotals from tool results and never calculates them itself.
- **Rate limits:** a short 429 is retried after Google's `retryDelay`. A daily-quota 429 puts that model in cooldown and the backend falls back to the next model.

## Checkout and payment verification

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as Android
    participant BE as FastAPI
    participant PP as PayPal Sandbox

    User->>App: ticks "I approve paying $359.96" → Pay
    App->>BE: POST /payments/create-order {session, expected_total, user_confirmed}
    BE->>BE: recompute total from server cart (refuse if ≠ expected_total)
    BE->>PP: POST /v2/checkout/orders (intent CAPTURE, server amount, return/cancel → BE)
    PP-->>BE: order id + payer-action link
    BE-->>App: {order_id, approve_url}
    App->>PP: open approve_url in Chrome Custom Tab
    User->>PP: logs in (sandbox buyer) and approves
    PP->>BE: GET /payments/paypal/return?token=ORDER
    BE-->>App: hand-off page → intent:// / cartpilot://payment/return?order_id=ORDER
    Note over App,BE: The deep link is only a signal. Nothing is "paid" yet.
    App->>BE: POST /payments/capture-order {session, order_id}
    BE->>PP: POST /v2/checkout/orders/ORDER/capture (idempotency key)
    PP-->>BE: COMPLETED + capture
    BE->>BE: verify order id, COMPLETED capture, amount & currency == snapshot
    BE-->>App: verified receipt (purchased items removed from cart)
    App-->>User: Payment successful · order ID · capture ID
```

**If the deep link never arrives** (the tab was closed or the browser blocked the custom scheme), the app calls `GET /payments/orders/{id}` when it resumes:

- `APPROVED` → capture
- `COMPLETED` → receipt
- anything else → "Payment not completed", with options to reopen PayPal or start over

Capture is idempotent, so a deep link and the resume check together can't charge twice.

## Code map

| Area | Path |
|---|---|
| Android screens | `android/app/src/main/java/com/cartpilot/ui/{home,products,cart,checkout,success}` |
| Android data layer | `…/data/{model,remote,repository}`, `…/domain/{model,repository}`, `…/core` |
| API routers | `backend/app/api/` |
| Agent | `backend/app/services/ai/` (`provider.py`, `gemini_provider.py`, `tools.py`, `agent_service.py`, `prompts.py`) |
| Catalogue and cart | `backend/app/services/products/`, `backend/app/services/cart/`, `backend/seed/products.json` |
| Payments | `backend/app/services/payments/` (`paypal_client.py`, `payment_service.py`) |
| Tests | `backend/tests/` (pytest, fake PayPal, scripted LLM), `android/app/src/test/` (ViewModels, API contract, screenshots) |
