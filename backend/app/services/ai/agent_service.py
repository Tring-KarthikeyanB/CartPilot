"""The shopping agent: runs a bounded tool-calling loop over an LLMProvider for one chat turn."""

import logging
import re
import threading
from collections import OrderedDict
from dataclasses import dataclass

from app.core.errors import AppError
from app.schemas.cart import BudgetCheck, CartSummary
from app.schemas.product import Product
from app.services.ai.prompts import SYSTEM_PROMPT
from app.services.ai.provider import LLMProvider, LLMProviderError, Message, ToolResult
from app.services.ai.tools import CART_MUTATING_TOOLS, TOOL_SPECS, ToolExecutor, ToolOutcome
from app.services.cart.cart_service import CartService, validate_session_id
from app.services.products.catalog_service import CatalogService

log = logging.getLogger(__name__)

MAX_STEPS = 8  # model calls per user turn
MAX_CALLS_PER_STEP = 8  # tool calls executed per model step
MAX_HISTORY_MESSAGES = 40
MAX_SESSIONS = 2_000
MAX_PRODUCT_CARDS = 8


class AIUnavailableError(AppError):
    status_code = 503
    code = "ai_unavailable"


@dataclass
class AgentTurn:
    reply: str
    products: list[Product]
    actions: list[ToolOutcome]
    cart: CartSummary
    budget: BudgetCheck | None
    cart_updated: bool


class AgentService:
    def __init__(self, provider: LLMProvider | None, catalog: CatalogService, carts: CartService):
        self.provider = provider
        self.catalog = catalog
        self.carts = carts
        self._history: "OrderedDict[str, list[Message]]" = OrderedDict()
        self._locks: dict[str, threading.Lock] = {}
        self._guard = threading.Lock()

    # ---------- session state ----------

    def _session_lock(self, session_id: str) -> threading.Lock:
        with self._guard:
            return self._locks.setdefault(session_id, threading.Lock())

    def _load_history(self, session_id: str) -> list[Message]:
        with self._guard:
            return list(self._history.get(session_id, []))

    def _save_history(self, session_id: str, messages: list[Message]) -> None:
        # Trim from the front, but only at a user-text boundary so tool calls/results stay paired.
        if len(messages) > MAX_HISTORY_MESSAGES:
            cut = len(messages) - MAX_HISTORY_MESSAGES
            while cut < len(messages) and not (messages[cut].role == "user" and messages[cut].text):
                cut += 1
            messages = messages[cut:]
        with self._guard:
            self._history[session_id] = messages
            self._history.move_to_end(session_id)
            while len(self._history) > MAX_SESSIONS:
                old, _ = self._history.popitem(last=False)
                self._locks.pop(old, None)

    def reset(self, session_id: str) -> None:
        validate_session_id(session_id)
        with self._guard:
            self._history.pop(session_id, None)

    # ---------- chat ----------

    def chat(self, session_id: str, user_message: str) -> AgentTurn:
        validate_session_id(session_id)
        if self.provider is None:
            raise AIUnavailableError("The AI assistant is not configured on the server (missing GEMINI_API_KEY).", code="ai_not_configured")
        text = user_message.strip()
        if not text:
            raise AppError("Message must not be empty.", code="empty_message")

        lock = self._session_lock(session_id)
        if not lock.acquire(timeout=90):
            raise AppError("Another message for this session is still being processed.", code="busy")
        try:
            return self._run_turn(session_id, text)
        finally:
            lock.release()

    def _run_turn(self, session_id: str, text: str) -> AgentTurn:
        executor = ToolExecutor(self.catalog, self.carts, session_id)
        messages = self._load_history(session_id) + [Message(role="user", text=text)]
        outcomes: list[ToolOutcome] = []
        reply = ""

        for _ in range(MAX_STEPS):
            try:
                response = self.provider.generate(SYSTEM_PROMPT, messages, TOOL_SPECS)
            except LLMProviderError as e:
                # Conversation history is not committed, so the turn can simply be retried.
                raise AIUnavailableError(str(e), code=e.code) from e

            messages.append(
                Message(role="assistant", text=response.text, tool_calls=response.tool_calls, provider_payload=response.provider_payload)
            )
            if not response.tool_calls:
                reply = response.text
                break

            results: list[ToolResult] = []
            for i, call in enumerate(response.tool_calls):
                if i < MAX_CALLS_PER_STEP:
                    outcome = executor.execute(call.name, call.args)
                    outcomes.append(outcome)
                    content = outcome.content
                    log.info("tool %s ok=%s args=%s", call.name, outcome.ok, call.args)
                else:
                    content = {"ok": False, "error": {"code": "too_many_calls", "message": "Too many tool calls in one step; retry fewer."}}
                # Every function call must get a response, in order.
                results.append(ToolResult(call_id=call.id, name=call.name, content=content))
            messages.append(Message(role="tool", tool_results=results))
        else:
            log.warning("Agent hit MAX_STEPS for session %s", session_id)

        if not reply:
            reply = self._fallback_reply(outcomes)
            messages.append(Message(role="assistant", text=reply))

        self._save_history(session_id, messages)

        cart = self.carts.get_summary(session_id)
        budget = next((o.budget for o in reversed(outcomes) if o.budget is not None), None)
        if budget is None and cart.budget is not None:
            budget = self.carts.check_budget(session_id)
        return AgentTurn(
            reply=reply,
            products=self._referenced_products(reply, outcomes),
            actions=outcomes,
            cart=cart,
            budget=budget,
            cart_updated=any(o.ok and o.name in CART_MUTATING_TOOLS for o in outcomes),
        )

    @staticmethod
    def _fallback_reply(outcomes: list[ToolOutcome]) -> str:
        done = [o.summary for o in outcomes if o.ok and o.name in CART_MUTATING_TOOLS]
        if done:
            return "Here's what I did:\n" + "\n".join(f"• {d}" for d in done) + "\nCheck the cart for the latest total."
        return "Sorry, I couldn't finish that request. Could you rephrase it or be a bit more specific?"

    def _referenced_products(self, reply: str, outcomes: list[ToolOutcome]) -> list[Product]:
        """Product cards for the app: catalogue products surfaced by tools this turn that the reply actually
        mentions (by name), plus anything added/replaced into the cart. Ordered by position in the reply."""
        surfaced: list[str] = []
        for o in outcomes:
            if o.ok:
                surfaced.extend(pid for pid in o.product_ids if pid not in surfaced)
        reply_lc = reply.lower()
        positions: dict[str, int] = {}
        for pid in surfaced:
            product = self.catalog.repository.find(pid)
            if product is None:
                continue
            pos = _mention_position(reply_lc, product)
            if pos is not None:
                positions[pid] = pos
        for o in outcomes:
            if o.ok and o.name in ("add_to_cart", "replace_product"):
                for pid in o.product_ids:
                    positions.setdefault(pid, len(reply_lc) + len(positions))
        ordered = sorted(positions, key=positions.get)[:MAX_PRODUCT_CARDS]
        return [self.catalog.get(pid) for pid in ordered]


def _mention_position(text_lc: str, product: Product) -> int | None:
    name = product.name.lower()
    candidates = [name, re.sub(r"\s*\(.*?\)", "", name).strip(), product.id]
    words = name.split()
    if len(words) >= 3:
        candidates.append(" ".join(words[:3]))
    for c in candidates:
        idx = text_lc.find(c)
        if idx >= 0:
            return idx
    return None
