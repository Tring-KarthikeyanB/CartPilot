# Demo video script (target 2:45)

**Setup before recording:**

- **Warm up the backend:** open `https://cartpilot-api.onrender.com/api/v1/health`, since a free Render instance sleeps.
- **Gemini quota:** confirm billing is on (or that the quota is fresh).
- **Start clean:** fresh install of the release APK, cart empty, PayPal sandbox buyer login at hand.
- **Phone:** screen recording on, notifications silenced.

| Time | On screen | Voice-over |
|---|---|---|
| 0:00–0:15 | App opens on the chat home | "This is CartPilot, a personal AI shopping agent. You say what you need and your budget, and it goes from intent to checkout, paid with PayPal." |
| 0:15–0:50 | Tap **"Build my home office setup under $500…"**. The typing indicator shows, then the reply with 4 product cards, "Added …" ticks and the cart pill | "One sentence. The agent, Gemini with function calling, saves my budget, searches four categories of a real catalogue, picks well-rated products and adds them to my cart. Every product, price and total here comes from the backend, never from the model's imagination." |
| 0:50–1:15 | Type **"Replace my headphones with a cheaper option and keep the total below $450."** Reply explains the swap and savings; cart pill updates | "I can refine in plain language. It finds a cheaper pair, swaps them, saves the new $450 limit and tells me exactly what changed." |
| 1:15–1:35 | Tap a product card to open the detail sheet. Open **Discover**, tap **Compare**, pick 3 keyboards, tap **Compare** to show the comparison sheet | "I can also browse and compare myself: cheapest, top rated and best value, side by side." |
| 1:35–1:55 | Open the **Cart**: budget bar, a "Swap and save" suggestion, quantity stepper | "The smart cart tracks my budget live and suggests cheaper swaps. I can also hand the cart back to the AI to optimize it." |
| 1:55–2:30 | **Checkout**: summary, tick the approval box, **Pay with PayPal**, Custom Tab, sandbox login, **Pay**. The app returns with "Confirming your payment…" | "Nothing is paid without my explicit approval. The amount is computed on the server, not by the app or the AI. PayPal opens securely, I approve, and CartPilot comes back." |
| 2:30–2:45 | **Payment successful** with order ID and PayPal capture ID | "Success is only shown after our backend captures the order with PayPal and verifies the amount. That's CartPilot: an agent that shops, and a checkout you can trust." |

**Backup lines if something is slow:** "Free-tier cold start; in production this is instant." If Gemini hits a rate limit: "The backend automatically falls back to another model."

**Before recording, check:**

- The PayPal page shows "CartPilot AI" as the merchant and the same total as the app.
- The capture ID on the success screen matches the PayPal sandbox dashboard.
- Keep the video under 3:00 and upload it as **Public** on YouTube.
