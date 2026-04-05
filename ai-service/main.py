"""
voice-brain — Dalai LLAMA Voice AI Service (Pipecat 0.0.43)

Run: cd voice-brain && python -m uvicorn main:app --host 0.0.0.0 --port 8601
Test: http://localhost:8601/test
"""
import sys
import os
import logging
from contextlib import asynccontextmanager
from collections import defaultdict
from datetime import datetime
from typing import Literal, Optional

from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket
from fastapi.staticfiles import StaticFiles
import httpx
from pydantic import BaseModel, Field
from pipecat.frames.frames import TTSSpeakFrame
import uvicorn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from config import settings
from auth.keycloak import require_auth
from pipeline.callbacks import pbx_core_client
from api.bot_config import router as bot_config_router
from api.provider_catalog import router as provider_catalog_router
from api.websocket_handler import handle_audio_websocket, active_calls, active_sessions
from api.test_console import router as test_router
from db.analytics_store import fetch_summary, fetch_timeline, store_event as persist_analytics_event
from db.database import close_database, init_database, ping_database, run_migrations
from db.provider_catalog_store import seed_provider_catalog

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
    stream=sys.stdout,
    force=True,
)
logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
logger = logging.getLogger(__name__)


class AnalyticsEventIn(BaseModel):
    call_id: str = Field(..., max_length=128)
    tenant_id: str = Field(..., max_length=128)
    product_code: str = Field(default="BASIC_PBX", max_length=64)
    speaker: Literal["CUSTOMER", "BOT", "AGENT", "SYSTEM"]
    event_type: str = Field(..., max_length=32)
    utterance: Optional[str] = None
    intent: Optional[str] = Field(default=None, max_length=128)
    intent_confidence: Optional[float] = None
    tone_label: Optional[str] = Field(default=None, max_length=64)
    tone_score: Optional[float] = None
    turn_index: Optional[int] = None
    metadata_json: Optional[dict] = None
    created_at: Optional[datetime] = None


class AdminSpeakIn(BaseModel):
    text: str = Field(..., min_length=1, max_length=500)
    role: Literal["ADMIN", "AGENT"] = "ADMIN"
    append_to_context: bool = False


class AnalyticsChatIn(BaseModel):
    question: str = Field(..., min_length=3, max_length=2000)


def _startup_warnings() -> list[str]:
    warnings: list[str] = []
    if settings.auth_enabled:
        if not settings.keycloak_realm:
            warnings.append("auth_enabled is true but KEYCLOAK_REALM is not set")
        if not settings.keycloak_client_id:
            warnings.append("auth_enabled is true but KEYCLOAK_CLIENT_ID is not set")
        if not (settings.keycloak_jwks_url or settings.keycloak_server_url):
            warnings.append("auth_enabled is true but KEYCLOAK_JWKS_URL or KEYCLOAK_SERVER_URL is not set")
        if settings.auth_allow_unsafe_dev_tokens:
            warnings.append("AUTH_ALLOW_UNSAFE_DEV_TOKENS is enabled; signature verification bypass is unsafe outside development")
    elif settings.auth_allow_unsafe_dev_tokens:
        warnings.append("AUTH_ALLOW_UNSAFE_DEV_TOKENS is set while AUTH_ENABLED is false; this setting has no effect")
    if settings.default_llm_provider.lower() == "openai" and not settings.openai_api_key:
        warnings.append("default_llm_provider is openai but VB_OPENAI_API_KEY is not set")
    if settings.default_llm_provider.lower() == "google" and not settings.google_api_key:
        warnings.append("default_llm_provider is google but VB_GOOGLE_API_KEY is not set")
    if settings.default_llm_provider.lower() == "vertex":
        if not settings.google_project_id:
            warnings.append("default_llm_provider is vertex but VB_GOOGLE_PROJECT_ID is not set")
        if not (settings.google_credentials_path or settings.google_credentials_json):
            warnings.append(
                "default_llm_provider is vertex but VB_GOOGLE_CREDENTIALS_PATH or VB_GOOGLE_CREDENTIALS_JSON is not set"
            )
    if settings.default_stt_provider.lower() == "deepgram" and not settings.deepgram_api_key:
        warnings.append("default_stt_provider is deepgram but VB_DEEPGRAM_API_KEY is not set")
    if settings.default_stt_provider.lower() == "openai" and not settings.openai_api_key:
        warnings.append("default_stt_provider is openai but VB_OPENAI_API_KEY is not set")
    if settings.default_tts_provider.lower() == "deepgram" and not settings.deepgram_api_key:
        warnings.append("default_tts_provider is deepgram but VB_DEEPGRAM_API_KEY is not set")
    if settings.default_tts_provider.lower() == "kokoro" and not settings.kokoro_base_url:
        warnings.append("default_tts_provider is kokoro but VB_KOKORO_BASE_URL is not set")
    if settings.default_tts_provider.lower() == "google":
        if not settings.google_project_id:
            warnings.append("default_tts_provider is google but VB_GOOGLE_PROJECT_ID is not set")
        if not (settings.google_credentials_path or settings.google_credentials_json):
            warnings.append(
                "default_tts_provider is google but VB_GOOGLE_CREDENTIALS_PATH or VB_GOOGLE_CREDENTIALS_JSON is not set"
            )
    if settings.default_llm_provider.lower() == "ollama" and not settings.ollama_base_url:
        warnings.append("default_llm_provider is ollama but VB_OLLAMA_BASE_URL is not set")
    if settings.google_credentials_path and settings.google_credentials_json:
        warnings.append("Both GOOGLE_CREDENTIALS_PATH and GOOGLE_CREDENTIALS_JSON are set; JSON will take precedence in runtime helpers")
    if settings.default_tts_provider.lower() == "google" and settings.google_tts_mode.lower() == "gemini" and not settings.google_credentials_json and not settings.google_credentials_path:
        warnings.append("google_tts_mode is gemini but Google TTS credentials are not configured")
    if settings.database_url is None:
        warnings.append("VB_DATABASE_URL is not set; persistent analytics endpoints will be unavailable")
    return warnings


async def _ask_analytics_llm(question: str, payload: dict) -> str:
    provider = settings.default_llm_provider.lower()
    system = (
        "You are an analytics copilot for a telephony AI platform. "
        "Answer only from the provided analytics data. "
        "Be concise, practical, and highlight trends, anomalies, and actionable insights. "
        "If the data is insufficient, say that clearly."
    )
    user = (
        f"Question:\n{question}\n\n"
        f"Analytics data:\n{payload}"
    )

    if provider == "ollama":
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{settings.ollama_base_url}/chat/completions",
                json={
                    "model": settings.ollama_llm_model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "temperature": 0.2,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()

    if provider == "openai":
        if not settings.openai_api_key:
            raise HTTPException(status_code=503, detail="OpenAI API key is not configured")
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                "https://api.openai.com/v1/chat/completions",
                headers={"Authorization": f"Bearer {settings.openai_api_key}"},
                json={
                    "model": settings.openai_llm_model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "temperature": 0.2,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()

    raise HTTPException(status_code=400, detail=f"Unsupported analytics chat provider: {provider}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    pbx_core_client.init(settings.pbx_core_url)
    init_database()
    applied_migrations = await run_migrations()
    await seed_provider_catalog()
    warnings = _startup_warnings()
    logger.info(
        "voice-brain starting: port=%d public_url=%s pbx=%s db=%s stt=%s tts=%s llm=%s rvc=%s",
        settings.port,
        settings.service_public_url,
        settings.pbx_core_url,
        bool(settings.database_url),
        settings.default_stt_provider,
        settings.default_tts_provider,
        settings.default_llm_provider,
        settings.rvc_enabled,
    )
    for warning in warnings:
        logger.warning("startup check: %s", warning)
    if applied_migrations:
        logger.info("database migrations applied: %s", ", ".join(applied_migrations))
    yield
    await pbx_core_client.close()
    await close_database()
    logger.info("voice-brain stopped")


app = FastAPI(
    title="Dalai LLAMA Voice Brain API",
    version="0.3.0",
    description=(
        "Telephony voice AI service for websocket audio, live transcripts, "
        "LLM orchestration, sentiment, and intent tracking."
    ),
    openapi_tags=[
        {"name": "system", "description": "Health, readiness, and service-level endpoints."},
        {"name": "providers", "description": "Provider catalog endpoints for UI configuration and test console use."},
        {"name": "bot-configs", "description": "Tenant-scoped bot/provider configuration bundles for PBX-Core and UI flows."},
        {"name": "test-console", "description": "Local browser test console endpoints and websocket helpers."},
        {"name": "calls", "description": "Active call inspection endpoints."},
        {"name": "analytics", "description": "Analytics timeline, summary, and question-answering endpoints."},
        {"name": "admin", "description": "Admin speech injection and active call control endpoints."},
    ],
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
)
app.mount("/static", StaticFiles(directory="static"), name="static")
app.include_router(provider_catalog_router)
app.include_router(bot_config_router)
app.include_router(test_router)


@app.websocket("/audio/{call_id}")
async def audio_ws(websocket: WebSocket, call_id: str,
                   tenant_id: str = "", product_code: str = "BASIC_PBX",
                   bot_id: str = None, direction: str = "INBOUND",
                   campaign_id: str = None, contact_id: str = None):
    await handle_audio_websocket(websocket, call_id, tenant_id, product_code,
                                  bot_id, direction, campaign_id, contact_id)


@app.get("/health", tags=["system"], summary="Health check")
async def health():
    return {
        "status": "healthy",
        "service_name": settings.service_name,
        "public_url": settings.service_public_url,
        "database_configured": bool(settings.database_url),
        "startup_warnings": _startup_warnings(),
        "active_calls": len(active_calls),
        "providers": {
            "stt": settings.default_stt_provider,
            "tts": settings.default_tts_provider,
            "llm": settings.default_llm_provider,
            "rvc": settings.rvc_enabled,
        },
    }


@app.get("/ready", tags=["system"], summary="Readiness check")
async def ready():
    checks = {
        "database": True,
        "llm": True,
        "stt": True,
        "tts": True,
    }
    details: dict[str, str] = {}

    if settings.database_url:
        checks["database"] = await ping_database()
        if not checks["database"]:
            details["database"] = "Database ping failed"

    if settings.default_llm_provider.lower() == "openai" and not settings.openai_api_key:
        checks["llm"] = False
        details["llm"] = "VB_OPENAI_API_KEY is not set"
    elif settings.default_llm_provider.lower() == "google" and not settings.google_api_key:
        checks["llm"] = False
        details["llm"] = "VB_GOOGLE_API_KEY is not set"
    elif settings.default_llm_provider.lower() == "ollama" and not settings.ollama_base_url:
        checks["llm"] = False
        details["llm"] = "VB_OLLAMA_BASE_URL is not set"
    elif settings.default_llm_provider.lower() == "vertex":
        if not settings.google_project_id:
            checks["llm"] = False
            details["llm"] = "VB_GOOGLE_PROJECT_ID is not set"
        elif not (settings.google_credentials_path or settings.google_credentials_json):
            checks["llm"] = False
            details["llm"] = "VB_GOOGLE_CREDENTIALS_PATH or VB_GOOGLE_CREDENTIALS_JSON is not set"

    if settings.default_stt_provider.lower() == "deepgram" and not settings.deepgram_api_key:
        checks["stt"] = False
        details["stt"] = "VB_DEEPGRAM_API_KEY is not set"
    elif settings.default_stt_provider.lower() == "openai" and not settings.openai_api_key:
        checks["stt"] = False
        details["stt"] = "VB_OPENAI_API_KEY is not set"

    if settings.default_tts_provider.lower() == "deepgram" and not settings.deepgram_api_key:
        checks["tts"] = False
        details["tts"] = "VB_DEEPGRAM_API_KEY is not set"
    elif settings.default_tts_provider.lower() == "kokoro" and not settings.kokoro_base_url:
        checks["tts"] = False
        details["tts"] = "VB_KOKORO_BASE_URL is not set"
    elif settings.default_tts_provider.lower() == "openai" and not settings.openai_api_key:
        checks["tts"] = False
        details["tts"] = "VB_OPENAI_API_KEY is not set"
    elif settings.default_tts_provider.lower() == "google":
        if not settings.google_project_id:
            checks["tts"] = False
            details["tts"] = "VB_GOOGLE_PROJECT_ID is not set"
        elif not (settings.google_credentials_path or settings.google_credentials_json):
            checks["tts"] = False
            details["tts"] = "VB_GOOGLE_CREDENTIALS_PATH or VB_GOOGLE_CREDENTIALS_JSON is not set"

    ready_state = all(checks.values())
    return {
        "status": "ready" if ready_state else "not_ready",
        "checks": checks,
        "details": details,
    }


@app.get("/calls", tags=["calls"], summary="List active calls")
async def calls(_: dict = Depends(require_auth)):
    return {"count": len(active_calls), "calls": [
        {"call_id": cid, "tenant_id": c.tenant_id, "product": c.product_code,
         "turns": c.turn_count, "intents": len(c.intent_log), "sentiment": c.current_sentiment}
        for cid, c in active_calls.items()
    ]}


@app.get(
    "/admin/calls/{call_id}/insights",
    tags=["admin"],
    summary="Get deep live insight for an active call",
)
async def admin_call_insights(call_id: str, _: dict = Depends(require_auth)):
    session = active_sessions.get(call_id)
    if not session:
        raise HTTPException(status_code=404, detail="Active call not found")

    ctx = session.ctx
    intent_distribution: dict[str, int] = {}
    for entry in ctx.intent_log:
        intent_distribution[entry.intent] = intent_distribution.get(entry.intent, 0) + 1

    customer_summary = {}
    bot_summary = {}
    if settings.database_url:
        customer_summary = await fetch_summary(tenant_id=ctx.tenant_id, speaker="CUSTOMER")
        bot_summary = await fetch_summary(tenant_id=ctx.tenant_id, speaker="BOT")

    return {
        "call_id": ctx.call_id,
        "tenant_id": ctx.tenant_id,
        "product_code": ctx.product_code,
        "direction": ctx.direction,
        "started_at": ctx.started_at,
        "turn_count": ctx.turn_count,
        "current_sentiment": ctx.current_sentiment,
        "sentiment_history": ctx.sentiment_history[-10:],
        "intent_distribution": intent_distribution,
        "recent_intents": [
            {
                "timestamp": entry.timestamp,
                "speaker": entry.speaker,
                "intent": entry.intent,
                "confidence": entry.confidence,
                "utterance": entry.utterance,
            }
            for entry in ctx.intent_log[-10:]
        ],
        "recent_conversation": ctx.conversation_history[-12:],
        "campaign": ctx.campaign,
        "contact": ctx.contact,
        "analytics_summary": {
            "customer": customer_summary,
            "bot": bot_summary,
        },
    }


@app.post(
    "/admin/calls/{call_id}/speak",
    tags=["admin"],
    summary="Inject admin speech into an active call",
)
async def admin_speak(call_id: str, payload: AdminSpeakIn, _: dict = Depends(require_auth)):
    session = active_sessions.get(call_id)
    if not session:
        raise HTTPException(status_code=404, detail="Active call not found")

    ctx = session.ctx
    await session.task.queue_frame(
        TTSSpeakFrame(text=payload.text, append_to_context=payload.append_to_context)
    )
    await persist_analytics_event(
        call_id=ctx.call_id,
        tenant_id=ctx.tenant_id,
        product_code=ctx.product_code,
        speaker="AGENT",
        event_type="admin_speak",
        utterance=payload.text,
        tone_label=payload.role,
        turn_index=ctx.turn_count,
        metadata_json={"source": payload.role, "append_to_context": payload.append_to_context},
    )
    return {"status": "queued", "call_id": call_id}


@app.get(
    "/analytics/intents/{tenant_id}",
    tags=["analytics"],
    summary="Get in-memory intent timeline for a tenant",
)
async def intent_timeline(tenant_id: str, _: dict = Depends(require_auth)):
    buckets: dict[str, dict] = defaultdict(
        lambda: {"count": 0, "top_intent": None, "top_intent_confidence": 0.0, "intents": defaultdict(int)}
    )

    for call in active_calls.values():
        if call.tenant_id != tenant_id:
            continue
        for entry in call.intent_log:
            bucket = entry.timestamp.astimezone().replace(second=0, microsecond=0).isoformat()
            item = buckets[bucket]
            item["count"] += 1
            item["intents"][entry.intent] += 1
            if entry.confidence >= item["top_intent_confidence"]:
                item["top_intent"] = entry.intent
                item["top_intent_confidence"] = entry.confidence

    timeline = []
    for bucket in sorted(buckets.keys()):
        item = buckets[bucket]
        timeline.append(
            {
                "timestamp": bucket,
                "count": item["count"],
                "top_intent": item["top_intent"],
                "top_intent_confidence": item["top_intent_confidence"],
                "intents": dict(item["intents"]),
            }
        )

    return {
        "tenant_id": tenant_id,
        "scope": "active_calls_only",
        "timeline": timeline,
        "note": "This endpoint only reflects in-memory active call data. Use PBX-Core persistence for historical dashboards.",
    }


@app.post("/analytics/events", tags=["analytics"], summary="Persist an analytics event")
async def create_analytics_event(event: AnalyticsEventIn, _: dict = Depends(require_auth)):
    if not settings.database_url:
        raise HTTPException(status_code=503, detail="Database is not configured")
    await persist_analytics_event(**event.model_dump())
    return {"status": "stored"}


@app.get(
    "/analytics/tenants/{tenant_id}/overview",
    tags=["analytics"],
    summary="Get tenant analytics overview from Postgres",
)
async def analytics_overview(
    tenant_id: str,
    from_ts: datetime | None = Query(default=None),
    to_ts: datetime | None = Query(default=None),
    _: dict = Depends(require_auth),
):
    if not settings.database_url:
        raise HTTPException(status_code=503, detail="Database is not configured")
    return {
        "tenant_id": tenant_id,
        "from": from_ts,
        "to": to_ts,
        "customer": await fetch_summary(tenant_id=tenant_id, speaker="CUSTOMER", start=from_ts, end=to_ts),
        "bot": await fetch_summary(tenant_id=tenant_id, speaker="BOT", start=from_ts, end=to_ts),
        "agent": await fetch_summary(tenant_id=tenant_id, speaker="AGENT", start=from_ts, end=to_ts),
    }


@app.get(
    "/analytics/tenants/{tenant_id}/{speaker}",
    tags=["analytics"],
    summary="Get analytics timeline for customer, bot, or agent",
)
async def analytics_timeline(
    tenant_id: str,
    speaker: Literal["customer", "bot", "agent"],
    from_ts: datetime | None = Query(default=None),
    to_ts: datetime | None = Query(default=None),
    _: dict = Depends(require_auth),
):
    if not settings.database_url:
        raise HTTPException(status_code=503, detail="Database is not configured")
    speaker_key = speaker.upper()
    return {
        "tenant_id": tenant_id,
        "speaker": speaker_key,
        "from": from_ts,
        "to": to_ts,
        "summary": await fetch_summary(tenant_id=tenant_id, speaker=speaker_key, start=from_ts, end=to_ts),
        "timeline": await fetch_timeline(tenant_id=tenant_id, speaker=speaker_key, start=from_ts, end=to_ts),
    }


@app.post(
    "/analytics/tenants/{tenant_id}/chat",
    tags=["analytics"],
    summary="Chat with tenant analytics data",
)
async def analytics_chat(
    tenant_id: str,
    payload: AnalyticsChatIn,
    from_ts: datetime | None = Query(default=None),
    to_ts: datetime | None = Query(default=None),
    _: dict = Depends(require_auth),
):
    if not settings.database_url:
        raise HTTPException(status_code=503, detail="Database is not configured")

    customer_summary = await fetch_summary(tenant_id=tenant_id, speaker="CUSTOMER", start=from_ts, end=to_ts)
    bot_summary = await fetch_summary(tenant_id=tenant_id, speaker="BOT", start=from_ts, end=to_ts)
    agent_summary = await fetch_summary(tenant_id=tenant_id, speaker="AGENT", start=from_ts, end=to_ts)
    customer_timeline = await fetch_timeline(tenant_id=tenant_id, speaker="CUSTOMER", start=from_ts, end=to_ts)
    bot_timeline = await fetch_timeline(tenant_id=tenant_id, speaker="BOT", start=from_ts, end=to_ts)
    agent_timeline = await fetch_timeline(tenant_id=tenant_id, speaker="AGENT", start=from_ts, end=to_ts)

    analytics_payload = {
        "tenant_id": tenant_id,
        "from": from_ts.isoformat() if from_ts else None,
        "to": to_ts.isoformat() if to_ts else None,
        "customer": {"summary": customer_summary, "timeline": customer_timeline[-120:]},
        "bot": {"summary": bot_summary, "timeline": bot_timeline[-120:]},
        "agent": {"summary": agent_summary, "timeline": agent_timeline[-120:]},
    }
    answer = await _ask_analytics_llm(payload.question, analytics_payload)
    return {
        "tenant_id": tenant_id,
        "from": from_ts,
        "to": to_ts,
        "question": payload.question,
        "answer": answer,
        "llm_provider": settings.default_llm_provider,
    }


if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        access_log=False,
        log_level=settings.log_level.lower(),
    )
