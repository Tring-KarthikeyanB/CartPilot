"""Gemini implementation of LLMProvider (google-genai SDK, manual function calling).

Rate limits: Gemini quotas are per model. Short per-minute 429s are retried on the same model using the
server's retryDelay; long ones (e.g. the free tier's daily cap) put that model in cooldown and the request
moves to the next model in the fallback chain. Conversation turns are portable across Gemini models
(verified: a function-calling turn started on one model continues on another).
"""

import logging
import re
import threading
import time
from collections.abc import Callable, Sequence

from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.services.ai.provider import LLMProviderError, LLMResponse, Message, ToolCall, ToolSpec

log = logging.getLogger(__name__)

_DEFAULT_RETRY_DELAY = 5.0
_QUOTA_EXHAUSTED_AFTER = 120.0  # a retryDelay longer than this means "quota used up", not "slow down"


def _retry_delay(error_text: str) -> float:
    """Seconds to wait, from Gemini's RetryInfo ('retryDelay': '11s') or 'Please retry in 11.17s' (+ margin)."""
    m = re.search(r"retryDelay'?\"?:\s*'?\"?([\d.]+)s", error_text) or re.search(r"retry in ([\d.]+)s", error_text)
    return (float(m.group(1)) + 0.5) if m else _DEFAULT_RETRY_DELAY


class _RateLimited(Exception):
    def __init__(self, delay: float):
        self.delay = delay


class GeminiProvider:
    name = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        timeout_seconds: float = 60.0,
        fallback_models: Sequence[str] = (),
        max_retries: int = 2,
        max_retry_wait_seconds: float = 25.0,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ):
        if not api_key:
            raise LLMProviderError("GEMINI_API_KEY is not configured.")
        self.models = list(dict.fromkeys([model, *[m for m in fallback_models if m]]))
        self.max_retries = max_retries
        self.max_retry_wait_seconds = max_retry_wait_seconds
        self._sleep = sleep
        self._clock = clock
        self._cooldown_until: dict[str, float] = {}
        self._lock = threading.Lock()
        self._client = genai.Client(
            api_key=api_key, http_options=types.HttpOptions(timeout=int(timeout_seconds * 1000))
        )

    @property
    def model(self) -> str:
        return self.models[0]

    # ---------- conversion ----------

    @staticmethod
    def _to_contents(messages: list[Message]) -> list[types.Content]:
        contents: list[types.Content] = []
        for m in messages:
            if m.role == "user":
                contents.append(types.Content(role="user", parts=[types.Part.from_text(text=m.text)]))
            elif m.role == "assistant":
                if isinstance(m.provider_payload, types.Content):
                    # Replay verbatim so thought signatures attached to function calls are preserved.
                    contents.append(m.provider_payload)
                else:
                    parts = [types.Part.from_text(text=m.text)] if m.text else []
                    parts += [types.Part(function_call=types.FunctionCall(id=c.id, name=c.name, args=c.args)) for c in m.tool_calls]
                    contents.append(types.Content(role="model", parts=parts))
            elif m.role == "tool":
                parts = []
                for r in m.tool_results:
                    part = types.Part.from_function_response(name=r.name, response=r.content)
                    if r.call_id and part.function_response is not None:
                        part.function_response.id = r.call_id
                    parts.append(part)
                contents.append(types.Content(role="user", parts=parts))
        return contents

    # ---------- LLMProvider ----------

    def generate(self, system_prompt: str, messages: list[Message], tools: list[ToolSpec]) -> LLMResponse:
        config = types.GenerateContentConfig(
            system_instruction=system_prompt,
            temperature=0.3,
            tools=[
                types.Tool(
                    function_declarations=[
                        types.FunctionDeclaration(name=t.name, description=t.description, parameters_json_schema=t.parameters)
                        for t in tools
                    ]
                )
            ],
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        )
        contents = self._to_contents(messages)

        now = self._clock()
        with self._lock:
            candidates = [m for m in self.models if self._cooldown_until.get(m, 0) <= now]
        longest_wait = 0.0
        for model in candidates:
            try:
                response = self._generate_with(model, contents, config)
            except _RateLimited as rl:
                with self._lock:
                    self._cooldown_until[model] = self._clock() + rl.delay
                longest_wait = max(longest_wait, rl.delay)
                log.warning("Gemini model %s rate limited for %.0fs; trying next model", model, rl.delay)
                continue
            return self._parse(response)

        if not candidates or longest_wait > _QUOTA_EXHAUSTED_AFTER:
            raise LLMProviderError(
                "The AI assistant has reached its usage limit for now. Please try again later.",
                retryable=True, code="ai_quota_exhausted",
            )
        raise LLMProviderError(
            "The AI service is busy (rate limited). Please retry in a few seconds.", retryable=True, code="ai_rate_limited"
        )

    def _generate_with(self, model: str, contents: list[types.Content], config: types.GenerateContentConfig):
        for attempt in range(self.max_retries + 1):
            try:
                return self._client.models.generate_content(model=model, contents=contents, config=config)
            except genai_errors.ClientError as e:  # 4xx
                if getattr(e, "code", None) != 429:
                    log.warning("Gemini client error (%s): %s", model, str(e)[:500])
                    raise LLMProviderError(f"The AI service rejected the request ({getattr(e, 'code', '4xx')}).") from e
                delay = _retry_delay(str(e))
                if attempt < self.max_retries and delay <= self.max_retry_wait_seconds:
                    log.info("Gemini %s rate limited; retrying in %.1fs", model, delay)
                    self._sleep(delay)
                    continue
                raise _RateLimited(delay) from e
            except genai_errors.ServerError as e:  # 5xx
                if attempt < self.max_retries:
                    log.info("Gemini %s server error %s; retrying", model, getattr(e, "code", "5xx"))
                    self._sleep(2.0 * (attempt + 1))
                    continue
                log.warning("Gemini server error (%s): %s", model, str(e)[:500])
                raise LLMProviderError("The AI service is temporarily unavailable.", retryable=True) from e
            except Exception as e:  # network/timeouts
                log.warning("Gemini call failed (%s): %s", model, e)
                raise LLMProviderError("Could not reach the AI service.", retryable=True) from e
        raise AssertionError("unreachable")

    @staticmethod
    def _parse(response: types.GenerateContentResponse) -> LLMResponse:
        candidate = response.candidates[0] if response.candidates else None
        if candidate is None or candidate.content is None:
            reason = getattr(candidate, "finish_reason", None) if candidate else None
            raise LLMProviderError(f"The AI returned no answer (finish reason: {reason}).")
        parts = candidate.content.parts or []
        text = "".join(p.text for p in parts if p.text and not p.thought)
        calls = [
            ToolCall(id=p.function_call.id, name=p.function_call.name or "", args=dict(p.function_call.args or {}))
            for p in parts
            if p.function_call
        ]
        return LLMResponse(text=text.strip(), tool_calls=calls, provider_payload=candidate.content)
