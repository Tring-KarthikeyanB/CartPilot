from fastapi import APIRouter, Depends, Path, Request, Response

from app.api import cart as cart_api
from app.api import products as products_api
from app.api.deps import get_agent_service
from app.schemas.agent import AgentAction, ChatRequest, ChatResponse
from app.schemas.cart import SESSION_ID_PATTERN
from app.services.ai.agent_service import AgentService

router = APIRouter(prefix="/agent", tags=["agent"])


@router.post("/chat", response_model=ChatResponse)
def chat(body: ChatRequest, request: Request, agent: AgentService = Depends(get_agent_service)) -> ChatResponse:
    """One conversational turn with the shopping agent. The agent may search, compare and edit this
    session's cart via validated tools; it cannot pay. The returned cart is computed by the backend."""
    turn = agent.chat(body.session_id, body.message)
    return ChatResponse(
        session_id=body.session_id,
        reply=turn.reply,
        products=[products_api.present(p, request) for p in turn.products],
        actions=[AgentAction(tool=a.name, ok=a.ok, summary=a.summary) for a in turn.actions],
        cart=cart_api.present(turn.cart, request),
        cart_updated=turn.cart_updated,
        budget=turn.budget,
    )


@router.delete("/sessions/{session_id}", status_code=204)
def reset_conversation(
    session_id: str = Path(pattern=SESSION_ID_PATTERN), agent: AgentService = Depends(get_agent_service)
) -> Response:
    """Forget the conversation history for a session (the cart is kept)."""
    agent.reset(session_id)
    return Response(status_code=204)
