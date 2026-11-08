"""GeminiProvider unit tests with a stubbed SDK client (no network)."""

import pytest
from google.genai import errors, types

from app.services.ai.gemini_provider import GeminiProvider, _retry_delay
from app.services.ai.provider import LLMProviderError, Message, ToolCall, ToolResult
from app.services.ai.tools import TOOL_SPECS


def rate_limited(seconds: float = 3.2) -> errors.ClientError:
    return errors.ClientError(429, {"error": {"code": 429, "message": f"Quota exceeded. Please retry in {seconds}s.", "status": "RESOURCE_EXHAUSTED"}})


def model_response(*parts: types.Part) -> types.GenerateContentResponse:
    return types.GenerateContentResponse(candidates=[types.Candidate(content=types.Content(role="model", parts=list(parts)))])


class StubModels:
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.requests = []

    def generate_content(self, *, model, contents, config):
        self.requests.append((model, contents, config))
        out = self.outcomes.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


def provider(outcomes, **kw):
    sleeps: list[float] = []
    p = GeminiProvider("test-key", "gemini-test", sleep=sleeps.append, **kw)
    stub = StubModels(outcomes)
    p._client = type("C", (), {"models": stub})()
    return p, stub, sleeps


def test_parses_text_and_function_calls():
    p, stub, _ = provider([
        model_response(
            types.Part(text="thinking...", thought=True),
            types.Part(text="Let me search."),
            types.Part(function_call=types.FunctionCall(id="call-1", name="search_products", args={"category": "mice"})),
        )
    ])
    r = p.generate("sys", [Message(role="user", text="mouse")], TOOL_SPECS)
    assert r.text == "Let me search."  # thoughts are not shown to users
    assert r.tool_calls == [ToolCall(id="call-1", name="search_products", args={"category": "mice"})]
    _, contents, config = stub.requests[0]
    assert config.automatic_function_calling.disable is True
    assert [f.name for f in config.tools[0].function_declarations] == [t.name for t in TOOL_SPECS]


def test_replays_model_content_verbatim_and_pairs_function_responses():
    original = types.Content(role="model", parts=[types.Part(
        function_call=types.FunctionCall(id="call-1", name="get_cart_summary", args={}), thought_signature=b"sig")])
    p, stub, _ = provider([model_response(types.Part(text="done"))])
    p.generate("sys", [
        Message(role="user", text="cart?"),
        Message(role="assistant", tool_calls=[ToolCall("call-1", "get_cart_summary", {})], provider_payload=original),
        Message(role="tool", tool_results=[ToolResult("call-1", "get_cart_summary", {"ok": True})]),
    ], TOOL_SPECS)
    contents = stub.requests[0][1]
    assert contents[1] is original  # thought signature preserved
    fr = contents[2].parts[0].function_response
    assert (contents[2].role, fr.name, fr.id, fr.response) == ("user", "get_cart_summary", "call-1", {"ok": True})


def test_retries_rate_limit_using_server_delay():
    p, stub, sleeps = provider([rate_limited(3.2), model_response(types.Part(text="ok"))])
    assert p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS).text == "ok"
    assert sleeps == [pytest.approx(3.7)]


def test_gives_up_when_rate_limit_persists_or_wait_too_long():
    p, _, sleeps = provider([rate_limited(1), rate_limited(1), rate_limited(1)])
    with pytest.raises(LLMProviderError) as e:
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert e.value.code == "ai_rate_limited" and len(sleeps) == 2

    p, _, sleeps = provider([rate_limited(60)])
    with pytest.raises(LLMProviderError):
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert sleeps == []


def test_other_client_errors_are_not_retried():
    p, _, sleeps = provider([errors.ClientError(400, {"error": {"code": 400, "message": "bad", "status": "INVALID_ARGUMENT"}})])
    with pytest.raises(LLMProviderError) as e:
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert e.value.code == "ai_unavailable" and sleeps == []


def test_server_errors_retry_then_fail():
    err = errors.ServerError(503, {"error": {"code": 503, "message": "overloaded", "status": "UNAVAILABLE"}})
    p, _, sleeps = provider([err, model_response(types.Part(text="recovered"))])
    assert p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS).text == "recovered"
    assert len(sleeps) == 1


def test_empty_candidate_raises():
    p, _, _ = provider([types.GenerateContentResponse(candidates=[])])
    with pytest.raises(LLMProviderError):
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)


def daily_quota() -> errors.ClientError:
    return errors.ClientError(429, {"error": {
        "code": 429, "status": "RESOURCE_EXHAUSTED",
        "message": "Generate content requests per day limit exceeded on Free Tier. Please retry in 18h57.68s.",
        "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "64857s"}],
    }})


def test_falls_back_to_next_model_and_remembers_cooldown():
    clock = [1000.0]
    p, stub, sleeps = provider(
        [daily_quota(), model_response(types.Part(text="from lite")), model_response(types.Part(text="lite again"))],
        fallback_models=["lite-a", "lite-b"], clock=lambda: clock[0],
    )
    assert p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS).text == "from lite"
    assert [r[0] for r in stub.requests] == ["gemini-test", "lite-a"]
    assert sleeps == []  # daily quota is not worth waiting for
    # Primary stays in cooldown on the next request.
    p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert [r[0] for r in stub.requests] == ["gemini-test", "lite-a", "lite-a"]


def test_all_models_exhausted_reports_quota():
    p, _, _ = provider([daily_quota(), daily_quota()], fallback_models=["lite-a"])
    with pytest.raises(LLMProviderError) as e:
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert e.value.code == "ai_quota_exhausted"
    # Everything cooling down: fail fast without calling the API.
    with pytest.raises(LLMProviderError) as e:
        p.generate("sys", [Message(role="user", text="hi")], TOOL_SPECS)
    assert e.value.code == "ai_quota_exhausted"


def test_retry_delay_parsing():
    assert _retry_delay("... Please retry in 11.17s.") == pytest.approx(11.67)
    assert _retry_delay(str(daily_quota())) == pytest.approx(64857.5)
    assert _retry_delay("no hint") == 5.0


def test_requires_api_key():
    with pytest.raises(LLMProviderError):
        GeminiProvider("", "m")
