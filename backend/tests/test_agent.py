"""Agent tests with a scripted fake LLM provider (no network). Real-Gemini behaviour is checked manually."""

from collections.abc import Callable

import pytest

from app.api.deps import get_agent_service
from app.main import app
from app.services.ai.agent_service import MAX_STEPS, AgentService, AIUnavailableError
from app.services.ai.provider import LLMProviderError, LLMResponse, Message, ToolCall
from app.services.ai.tools import TOOL_SPECS, ToolExecutor
from tests.conftest import SESSION

Step = LLMResponse | Callable[[list[Message]], LLMResponse] | Exception


class ScriptedProvider:
    name = "scripted"

    def __init__(self, steps: list[Step]):
        self.steps = list(steps)
        self.calls: list[list[Message]] = []

    def generate(self, system_prompt, messages, tools):
        assert "CartPilot" in system_prompt and tools == TOOL_SPECS
        self.calls.append(list(messages))
        step = self.steps.pop(0)
        if isinstance(step, Exception):
            raise step
        return step(messages) if callable(step) else step


def calls(*pairs) -> LLMResponse:
    return LLMResponse(text="", tool_calls=[ToolCall(id=f"c{i}", name=n, args=a) for i, (n, a) in enumerate(pairs)])


def say(text: str) -> LLMResponse:
    return LLMResponse(text=text, tool_calls=[])


def last_tool_results(messages: list[Message]) -> list[dict]:
    return [r.content for r in messages[-1].tool_results]


@pytest.fixture
def make_agent(catalog, carts):
    def _make(steps):
        provider = ScriptedProvider(steps)
        return AgentService(provider, catalog, carts), provider

    return _make


# ---------- tool surface ----------

def test_tool_surface_is_exactly_the_seven_shopping_tools():
    assert [t.name for t in TOOL_SPECS] == [
        "search_products", "compare_products", "add_to_cart", "remove_from_cart",
        "replace_product", "get_cart_summary", "validate_budget",
    ]
    assert not any("pay" in t.name or "checkout" in t.name or "order" in t.name for t in TOOL_SPECS)


def test_executor_validates_arguments(catalog, carts):
    ex = ToolExecutor(catalog, carts, SESSION)
    assert ex.execute("delete_everything", {}).content["error"]["code"] == "unknown_tool"
    assert ex.execute("add_to_cart", {"product_id": "kb-001", "quantity": 99}).content["error"]["code"] == "invalid_arguments"
    # The model cannot smuggle in a price.
    bad = ex.execute("add_to_cart", {"product_id": "kb-001", "price": 0.01})
    assert not bad.ok and "price" in bad.content["error"]["message"]
    assert ex.execute("add_to_cart", {"product_id": "kb-999"}).content["error"]["code"] == "product_not_found"
    assert ex.execute("add_to_cart", {"product_id": "ls-005"}).content["error"]["code"] == "out_of_stock"
    assert ex.execute("search_products", {"category": "toasters"}).content["error"]["code"] == "invalid_arguments"
    assert ex.execute("compare_products", {"product_ids": ["kb-001"]}).content["error"]["code"] == "invalid_arguments"
    assert carts.get_summary(SESSION).items == []


def test_executor_accepts_float_quantities_from_model(catalog, carts):
    out = ToolExecutor(catalog, carts, SESSION).execute("add_to_cart", {"product_id": "kb-001", "quantity": 2.0})
    assert out.ok and carts.get_summary(SESSION).items[0].quantity == 2


def test_search_tool_defaults_to_in_stock_and_caps_results(catalog, carts):
    out = ToolExecutor(catalog, carts, SESSION).execute("search_products", {"category": "laptop_stands"})
    ids = [p["id"] for p in out.content["products"]]
    assert "ls-005" not in ids and len(ids) <= 5
    assert all(set(p) >= {"id", "name", "price_usd", "rating", "stock_status"} for p in out.content["products"])


# ---------- agent loop ----------

def test_plain_reply_without_tools(make_agent):
    agent, _ = make_agent([say("Hi! What are you shopping for today?")])
    turn = agent.chat(SESSION, "hello")
    assert turn.reply.startswith("Hi!") and turn.actions == [] and not turn.cart_updated and turn.products == []


def test_home_office_build_flow(make_agent, carts):
    def final(messages):
        # The model sees backend-computed totals in tool results.
        budget = last_tool_results(messages)[0]
        assert budget["within_budget"] is True and budget["subtotal"] == 169.96
        return say(
            "Here's your setup:\n• Aurora K2 Low-Profile Wireless Keyboard ($49.99)\n• Glide Wireless Mouse ($19.99)\n"
            "• Studio Monitor Wired Headphones ($39.99)\n• Elevate Pro Adjustable Laptop Stand ($59.99)\n"
            "Subtotal $169.96, $330.04 left of your $500 budget."
        )

    agent, provider = make_agent([
        calls(("validate_budget", {"budget": 500})),
        calls(("search_products", {"category": "keyboards", "max_price": 150}),
              ("search_products", {"category": "mice", "max_price": 80}),
              ("search_products", {"category": "headphones", "max_price": 200}),
              ("search_products", {"category": "laptop_stands", "max_price": 80})),
        calls(("add_to_cart", {"product_id": "kb-001"}), ("add_to_cart", {"product_id": "ms-001"}),
              ("add_to_cart", {"product_id": "hp-001"}), ("add_to_cart", {"product_id": "ls-002"})),
        calls(("validate_budget", {})),
        final,
    ])
    turn = agent.chat(SESSION, "Build my home office setup under $500: keyboard, mouse, headphones, laptop stand")

    assert [i.product_id for i in turn.cart.items] == ["kb-001", "ms-001", "hp-001", "ls-002"]
    assert turn.cart.subtotal == 169.96 and turn.cart.budget == 500 and not turn.cart.over_budget
    assert turn.cart_updated and turn.budget.within_budget
    assert [p.id for p in turn.products] == ["kb-001", "ms-001", "hp-001", "ls-002"]
    assert sum(a.name == "add_to_cart" for a in turn.actions) == 4 and all(a.ok for a in turn.actions)
    assert len(provider.calls) == 5
    # Every function call got a paired function response, in order.
    msgs = provider.calls[-1]
    for i, m in enumerate(msgs):
        if m.role == "assistant" and m.tool_calls:
            assert [r.call_id for r in msgs[i + 1].tool_results] == [c.id for c in m.tool_calls]


def test_follow_up_turn_uses_history_and_replaces_item(make_agent, carts):
    carts.add_item(SESSION, "hp-004")
    carts.add_item(SESSION, "kb-005")

    def check_history(messages):
        assert any(m.role == "user" and m.text == "first" for m in messages)
        return calls(("replace_product", {"old_product_id": "hp-004", "new_product_id": "hp-003"}),
                     ("validate_budget", {"budget": 450}))

    agent, _ = make_agent([
        say("ok"),
        check_history,
        say("Swapped to Halo ANC Wireless Headphones ($149.99), saving $130. Subtotal $229.98, within your $450 budget."),
    ])
    agent.chat(SESSION, "first")
    turn = agent.chat(SESSION, "Replace my headphones with a cheaper option and keep the total below $450")
    assert [i.product_id for i in turn.cart.items] == ["hp-003", "kb-005"]
    assert turn.cart.subtotal == 229.98 and turn.cart.budget == 450
    assert [p.id for p in turn.products] == ["hp-003"]


def test_model_recovers_from_tool_errors(make_agent, carts):
    def after_error(messages):
        err = last_tool_results(messages)[0]
        assert err["ok"] is False and err["error"]["code"] == "product_not_found"
        return calls(("add_to_cart", {"product_id": "kb-001"}))

    agent, _ = make_agent([
        calls(("add_to_cart", {"product_id": "kb-404"})),
        after_error,
        say("Added the Aurora K2 Low-Profile Wireless Keyboard ($49.99)."),
    ])
    turn = agent.chat(SESSION, "add a keyboard")
    assert [a.ok for a in turn.actions] == [False, True]
    assert [i.product_id for i in turn.cart.items] == ["kb-001"]


def test_products_only_include_grounded_mentions(make_agent):
    agent, _ = make_agent([
        calls(("search_products", {"category": "headphones", "max_price": 100})),
        say("Try the Studio Monitor Wired Headphones ($39.99) or the Vibe Wireless Over-Ear Headphones ($69.99). "
            "The SuperBass 9000 is great too."),  # hallucinated product: no card
    ])
    turn = agent.chat(SESSION, "headphones under $100")
    assert [p.id for p in turn.products] == ["hp-001", "hp-005"]
    assert not turn.cart_updated


def test_loop_is_bounded(make_agent, carts):
    agent, provider = make_agent([calls(("get_cart_summary", {}))] * MAX_STEPS)
    turn = agent.chat(SESSION, "loop forever")
    assert len(provider.calls) == MAX_STEPS
    assert turn.reply  # fallback reply, no crash


def test_provider_failure_raises_and_does_not_commit_history(make_agent):
    agent, provider = make_agent([
        LLMProviderError("The AI service is busy (rate limited).", retryable=True, code="ai_rate_limited"),
        say("hello again"),
    ])
    with pytest.raises(AIUnavailableError) as exc:
        agent.chat(SESSION, "first try")
    assert exc.value.code == "ai_rate_limited"
    agent.chat(SESSION, "second try")
    assert not any(m.text == "first try" for m in provider.calls[-1])


def test_not_configured(catalog, carts):
    with pytest.raises(AIUnavailableError) as exc:
        AgentService(None, catalog, carts).chat(SESSION, "hi")
    assert exc.value.code == "ai_not_configured"


def test_reset_clears_history(make_agent):
    agent, provider = make_agent([say("a"), say("b")])
    agent.chat(SESSION, "remember me")
    agent.reset(SESSION)
    agent.chat(SESSION, "again")
    assert [m.text for m in provider.calls[-1]] == ["again"]


def test_agent_only_touches_its_own_session(make_agent, carts):
    agent, _ = make_agent([calls(("add_to_cart", {"product_id": "kb-001"})), say("done")])
    agent.chat(SESSION, "add keyboard")
    assert carts.get_summary("someone-elses-cart").items == []


# ---------- API ----------

@pytest.fixture
def agent_client(client, catalog, carts):
    def install(steps):
        provider = ScriptedProvider(steps)
        app.dependency_overrides[get_agent_service] = lambda: AgentService(provider, catalog, carts)
        return provider

    return install


def test_chat_api(client, agent_client):
    agent_client([
        calls(("add_to_cart", {"product_id": "ms-002"})),
        say("Added the Precision Pro Ergonomic Mouse ($59.99). Subtotal $59.99."),
    ])
    r = client.post("/api/v1/agent/chat", json={"session_id": SESSION, "message": "ergonomic mouse please"})
    assert r.status_code == 200
    body = r.json()
    assert body["reply"].startswith("Added")
    assert body["cart"]["subtotal"] == 59.99 and body["cart_updated"] is True
    assert body["products"][0]["id"] == "ms-002" and body["products"][0]["image_url"].startswith("http://testserver/static/")
    assert body["actions"] == [{"tool": "add_to_cart", "ok": True, "summary": "Added Precision Pro Ergonomic Mouse"}]


def test_chat_api_validation_and_ai_errors(client, agent_client):
    assert client.post("/api/v1/agent/chat", json={"session_id": "x", "message": "hi"}).status_code == 422
    assert client.post("/api/v1/agent/chat", json={"session_id": SESSION, "message": ""}).status_code == 422
    assert client.post("/api/v1/agent/chat", json={"session_id": SESSION, "message": "x" * 2001}).status_code == 422

    agent_client([LLMProviderError("Could not reach the AI service.", retryable=True)])
    r = client.post("/api/v1/agent/chat", json={"session_id": SESSION, "message": "hi"})
    assert r.status_code == 503 and r.json()["error"]["code"] == "ai_unavailable"


def test_reset_api(client, agent_client):
    agent_client([])
    assert client.delete(f"/api/v1/agent/sessions/{SESSION}").status_code == 204
