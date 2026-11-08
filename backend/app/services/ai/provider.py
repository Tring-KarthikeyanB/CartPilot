"""Provider-neutral LLM interface. The shopping agent only depends on these types, so another
LLM vendor can be added by implementing `LLMProvider` without touching the agent workflow."""

from dataclasses import dataclass, field
from typing import Any, Literal, Protocol


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]  # JSON Schema (object)


@dataclass(frozen=True)
class ToolCall:
    id: str | None
    name: str
    args: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    call_id: str | None
    name: str
    content: dict[str, Any]


@dataclass
class Message:
    role: Literal["user", "assistant", "tool"]
    text: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    tool_results: list[ToolResult] = field(default_factory=list)
    # Provider-specific payload needed to replay this turn verbatim (e.g. Gemini thought signatures).
    provider_payload: Any = None


@dataclass(frozen=True)
class LLMResponse:
    text: str
    tool_calls: list[ToolCall]
    provider_payload: Any = None


class LLMProviderError(Exception):
    def __init__(self, message: str, *, retryable: bool = False, code: str = "ai_unavailable"):
        super().__init__(message)
        self.retryable = retryable
        self.code = code  # "ai_unavailable" | "ai_rate_limited" | ...


class LLMProvider(Protocol):
    name: str

    def generate(self, system_prompt: str, messages: list[Message], tools: list[ToolSpec]) -> LLMResponse:
        """Run one model step. Must raise LLMProviderError on transport/quota/model failures."""
        ...
