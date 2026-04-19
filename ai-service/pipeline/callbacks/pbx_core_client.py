"""Async HTTP client for PBX-Core callbacks."""
import httpx
import logging

logger = logging.getLogger(__name__)

_client: httpx.AsyncClient | None = None
_base_url: str = "http://pbx-core.internal:8080"


def init(base_url: str):
    """Call once at startup with PBX-Core URL."""
    global _base_url
    _base_url = base_url


def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            base_url=_base_url,
            timeout=httpx.Timeout(10.0, connect=5.0),
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
        )
    return _client


async def close():
    global _client
    if _client and not _client.is_closed:
        await _client.aclose()


async def fetch_ai_config(tenant_id: str, bot_id: str = None, call_id: str = None,
                          campaign_id: str = None, contact_id: str = None) -> dict:
    params = {}
    if bot_id:
        params["botId"] = bot_id
    if call_id:
        params["callId"] = call_id
    if campaign_id:
        params["campaignId"] = campaign_id
    if contact_id:
        params["contactId"] = contact_id
    try:
        resp = await _get_client().get(f"/internal/ai/config/{tenant_id}", params=params)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("Failed to fetch AI config: tenant=%s err=%s", tenant_id, e)
        return {}


async def send_live_transcript(
    call_id: str, tenant_id: str, speaker: str, text: str,
    is_final: bool = False, confidence: float = 0.0,
    language: str = "en", timestamp_ms: int = 0,
):
    try:
        await _get_client().post("/internal/ai/transcript/live", json={
            "call_id": call_id, "tenant_id": tenant_id, "speaker": speaker,
            "text": text, "is_final": is_final, "confidence": confidence,
            "language": language, "timestamp_ms": timestamp_ms,
        })
    except Exception as e:
        logger.warning("Live transcript failed: %s", e)


async def send_final_transcript(
    call_id: str, transcript_summary: str, ai_minutes: float = 0, total_turns: int = 0,
    tenant_id: str = None, full_transcript: list[dict] = None,
):
    try:
        payload = {
            "call_id": call_id, "transcript_summary": transcript_summary,
            "ai_minutes": ai_minutes, "total_turns": total_turns,
        }
        if tenant_id:
            payload["tenant_id"] = tenant_id
        if full_transcript:
            payload["full_transcript"] = full_transcript
        await _get_client().post("/internal/ai/transcript/final", json=payload)
    except Exception as e:
        logger.warning("Final transcript failed: %s", e)


async def send_sentiment(
    call_id: str, tenant_id: str, score: float, label: str = "NEUTRAL",
    emotion_history: list = None,
):
    try:
        await _get_client().post("/internal/ai/sentiment", json={
            "call_id": call_id, "tenant_id": tenant_id,
            "score": score, "label": label, "emotion_history": emotion_history or [],
        })
    except Exception as e:
        logger.warning("Sentiment failed: %s", e)


async def escalate(
    call_id: str, tenant_id: str, escalation_type: str, target: str = None,
    reason: str = None, transcript_summary: str = None,
    sentiment_score: float = None, intent: str = None,
) -> dict:
    try:
        resp = await _get_client().post("/internal/ai/escalation", json={
            "call_id": call_id, "tenant_id": tenant_id,
            "escalation_type": escalation_type, "target": target,
            "reason": reason, "transcript_summary": transcript_summary,
            "sentiment_score": sentiment_score, "intent": intent,
        })
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("Escalation failed: %s", e)
        return {"error": str(e)}
