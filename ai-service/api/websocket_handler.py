"""WebSocket handler — FreeSWITCH mod_audio_stream connects here."""
import asyncio
import logging
from copy import deepcopy
from dataclasses import dataclass
from fastapi import WebSocket, WebSocketDisconnect
from pipecat.frames.frames import TTSSpeakFrame
from pipecat.pipeline.task import PipelineTask

from pipeline.pipecat_pipeline import build_pipeline
from pipeline.callbacks import pbx_core_client
from models.call_context import CallContext, ProviderConfig, BotConfig
from config import settings
from db.analytics_store import store_event as persist_analytics_event

logger = logging.getLogger(__name__)

active_calls: dict[str, CallContext] = {}


@dataclass
class ActiveCallSession:
    ctx: CallContext
    task: PipelineTask


active_sessions: dict[str, ActiveCallSession] = {}


def _uses_native_audio_llm(ctx: CallContext) -> bool:
    return (ctx.providers.llm_provider or "").lower() == "gemini_live"


async def _queue_initial_greeting(ctx: CallContext, task: PipelineTask):
    await asyncio.sleep(0.05)
    if not ctx.bot.greeting_message:
        return
    logger.info("Queueing initial greeting: call=%s text=%s", ctx.call_id, ctx.bot.greeting_message[:200])
    ctx.add_conversation_turn("assistant", ctx.bot.greeting_message)
    asyncio.create_task(
        persist_analytics_event(
            call_id=ctx.call_id,
            tenant_id=ctx.tenant_id,
            product_code=ctx.product_code,
            speaker="BOT",
            event_type="greeting",
            utterance=ctx.bot.greeting_message,
            intent="greeting",
            intent_confidence=1.0,
            turn_index=ctx.turn_count,
        )
    )
    await task.queue_frame(TTSSpeakFrame(text=ctx.bot.greeting_message, append_to_context=False))
    _store_test_event(
        ctx.call_id,
        {"type": "bot_response", "text": ctx.bot.greeting_message, "intent": "greeting", "intent_confidence": 1.0},
    )


def _default_llm_model(provider: str) -> str:
    provider_name = (provider or settings.default_llm_provider).lower()
    if provider_name == "ollama":
        return settings.ollama_llm_model
    if provider_name == "gemini_live":
        return settings.google_gemini_live_llm_model
    if provider_name == "google":
        return settings.google_llm_model
    if provider_name == "vertex":
        return settings.vertex_llm_model
    return settings.openai_llm_model


def _default_tts_config(provider: str) -> tuple[str | None, str | None]:
    provider_name = (provider or settings.default_tts_provider).lower()
    if provider_name == "indic_tts":
        return None, settings.indic_tts_voice
    if provider_name == "kokoro":
        return None, settings.kokoro_voice_female
    if provider_name == "gemini_live":
        return None, settings.google_gemini_live_voice
    if provider_name == "google":
        return None, settings.google_tts_voice
    if provider_name == "deepgram":
        return None, settings.deepgram_tts_model
    return settings.openai_tts_model, settings.openai_tts_voice


def _store_test_event(call_id: str, event: dict):
    try:
        from api.test_console import add_event
        add_event(call_id, event)
    except Exception:
        pass


def _clean_mapping(value: dict | None) -> dict:
    if not isinstance(value, dict):
        return {}
    return {k: v for k, v in value.items() if v not in (None, "", [], {})}


def _merge_mappings(*values: dict | None) -> dict:
    merged: dict = {}
    for value in values:
        if not isinstance(value, dict):
            continue
        for key, item in value.items():
            if item not in (None, "", [], {}):
                merged[key] = item
    return merged


def _resolve_provider_options(ai_config: dict, bot_data: dict, modality: str, provider: str) -> dict:
    provider_name = (provider or "").lower()
    provider_configs = _merge_mappings(
        ai_config.get("provider_configs"),
        bot_data.get("provider_configs"),
    )
    provider_credentials = _merge_mappings(
        ai_config.get("provider_credentials"),
        bot_data.get("provider_credentials"),
    )
    tenant_provider_credentials = _merge_mappings(
        ai_config.get("tenant_provider_credentials"),
        bot_data.get("tenant_provider_credentials"),
    )
    modality_configs = provider_configs.get(modality) if isinstance(provider_configs.get(modality), dict) else {}
    return _merge_mappings(
        provider_configs.get(provider_name) if isinstance(provider_configs.get(provider_name), dict) else None,
        modality_configs.get(provider_name) if isinstance(modality_configs, dict) else None,
        provider_credentials.get(provider_name) if isinstance(provider_credentials.get(provider_name), dict) else None,
        tenant_provider_credentials.get(provider_name) if isinstance(tenant_provider_credentials.get(provider_name), dict) else None,
        ai_config.get(f"{modality}_options"),
        bot_data.get(f"{modality}_options"),
    )


def _resolve_api_key(ai_config: dict, explicit_key: str, options: dict) -> str | None:
    return (
        ai_config.get(explicit_key)
        or options.get("api_key")
        or options.get("token")
        or None
    )


def _apply_test_overrides(ai_config: dict, overrides: dict | None) -> dict:
    if not overrides:
        return ai_config

    payload = deepcopy(ai_config)
    bot = deepcopy(payload.get("bot") or {})
    stt_options = deepcopy(payload.get("stt_options") or {})
    llm_options = deepcopy(payload.get("llm_options") or {})
    tts_options = deepcopy(payload.get("tts_options") or {})

    if overrides.get("stt_provider"):
        payload["stt_provider"] = overrides["stt_provider"]
    if overrides.get("llm_provider"):
        payload["llm_provider"] = overrides["llm_provider"]
    if overrides.get("tts_provider"):
        payload["tts_provider"] = overrides["tts_provider"]

    if overrides.get("stt_provider") and not overrides.get("stt_model"):
        payload["stt_model"] = None
    if overrides.get("llm_provider") and not overrides.get("llm_model"):
        payload["llm_model"] = _default_llm_model(overrides["llm_provider"])
    if overrides.get("tts_provider") and not overrides.get("tts_model"):
        payload["tts_model"] = None

    if overrides.get("stt_model"):
        payload["stt_model"] = overrides["stt_model"]
    if overrides.get("llm_model"):
        payload["llm_model"] = overrides["llm_model"]
    if overrides.get("tts_model"):
        payload["tts_model"] = overrides["tts_model"]
    if overrides.get("stt_api_key"):
        payload["stt_api_key"] = overrides["stt_api_key"]
    if overrides.get("llm_api_key"):
        payload["llm_api_key"] = overrides["llm_api_key"]
    if overrides.get("tts_api_key"):
        payload["tts_api_key"] = overrides["tts_api_key"]

    if overrides.get("language"):
        bot["language"] = overrides["language"]
    if overrides.get("tts_voice"):
        payload["tts_voice"] = overrides["tts_voice"]
        bot["voice_id"] = overrides["tts_voice"]
    if overrides.get("tts_gender"):
        payload["tts_gender"] = overrides["tts_gender"]
        bot["voice_gender"] = overrides["tts_gender"]
    if overrides.get("tts_provider"):
        bot["voice_provider"] = overrides["tts_provider"]
    if overrides.get("voice_speed"):
        bot["voice_speed"] = overrides["voice_speed"]
        tts_options["speed"] = overrides["voice_speed"]
    if overrides.get("tts_sample_rate"):
        tts_options["sample_rate"] = overrides["tts_sample_rate"]
    if overrides.get("llm_base_url"):
        llm_options["base_url"] = overrides["llm_base_url"]
    if overrides.get("tts_base_url"):
        tts_options["base_url"] = overrides["tts_base_url"]
    if overrides.get("stt_base_url"):
        stt_options["base_url"] = overrides["stt_base_url"]
    if overrides.get("stt_location"):
        stt_options["location"] = overrides["stt_location"]
    if overrides.get("stt_credentials_path"):
        stt_options["credentials_path"] = overrides["stt_credentials_path"]
    if overrides.get("stt_credentials_json"):
        stt_options["credentials_json"] = overrides["stt_credentials_json"]

    payload["bot"] = bot
    payload["stt_options"] = stt_options
    payload["llm_options"] = llm_options
    payload["tts_options"] = tts_options
    return payload


async def handle_audio_websocket(
    websocket: WebSocket, call_id: str,
    tenant_id: str = "", product_code: str = "BASIC_PBX",
    bot_id: str = None, direction: str = "INBOUND",
    campaign_id: str = None, contact_id: str = None,
    test_overrides: dict | None = None,
):
    """
    FreeSWITCH dialplan connects:
      ws://voice-brain:8600/audio/${uuid}
        ?tenant_id=...&product_code=...&bot_id=...
        &campaign_id=...&contact_id=...&direction=INBOUND
    """
    print(f"WS HANDLER ENTERED call={call_id} tenant={tenant_id} product={product_code} direction={direction}", flush=True)
    logger.info("WS handler entered: call=%s tenant=%s product=%s direction=%s", call_id, tenant_id, product_code, direction)
    await websocket.accept()
    print(f"WS ACCEPTED call={call_id}", flush=True)
    logger.info("Connected: call=%s tenant=%s product=%s campaign=%s contact=%s",
                call_id, tenant_id, product_code, campaign_id, contact_id)

    try:
        logger.info("Fetching AI config: call=%s bot=%s campaign=%s contact=%s", call_id, bot_id, campaign_id, contact_id)
        ai_config = await pbx_core_client.fetch_ai_config(
            tenant_id, bot_id, call_id, campaign_id, contact_id)
        if not ai_config:
            default_llm_provider = settings.default_llm_provider
            default_tts_provider = settings.default_tts_provider
            default_tts_model, default_tts_voice = _default_tts_config(default_tts_provider)
            # Fallback for test mode (no PBX-Core running)
            ai_config = {
                "ai_enabled": True, "stt_enabled": True, "sentiment_enabled": True,
                "product_code": product_code,
                "stt_provider": settings.default_stt_provider,
                "llm_provider": default_llm_provider,
                "llm_model": _default_llm_model(default_llm_provider),
                "tts_provider": default_tts_provider,
                "tts_model": default_tts_model,
                "tts_voice": default_tts_voice,
                "bot": {
                    "name": "Dalai LLAMA Test Bot",
                    "system_prompt": (
                        "You are a helpful sales assistant for Dalai LLAMA, an AI-powered cloud communication platform. "
                        "You speak Hinglish (mix of Hindi and English) naturally, like a friendly Indian salesperson. "
                        "Use Hindi words naturally mixed with English. For example: "
                        "'Haan ji, main aapki help kar sakti hoon. Aapko kya chahiye?' "
                        "'Hamara Basic plan sirf 999 rupaye per month hai, ismein 5 agents milte hain.' "
                        "Be warm, professional, and helpful. First understand the caller, ask their name, company, and current phone system need before pitching."
                    ),
                    "greeting_message": "Namaste, main Priya hoon Dalai LLAMA se. Sabse pehle aapka naam bata dijiye.",
                    "goodbye_message": "Dhanyavaad aapka! Aapka din shubh ho!",
                    "language": "hi",
                    "voice_gender": "female",
                    "allowed_intents": ["interested", "pricing", "not_interested", "technical_support", "greeting", "goodbye"],
                    "escalation_intents": {
                        "interested": {"threshold": 0.85, "action": "TRANSFER_QUEUE", "target": "sales"},
                        "not_interested": {"threshold": 0.95, "action": "HANGUP", "target": ""},
                    },
                    "barge_in_enabled": True,
                    "sentiment_tracking": True,
                    "max_turns": 30,
                    "max_duration_seconds": 600,
                },
            }
            logger.warning("Using fallback AI config: call=%s", call_id)
        else:
            logger.info("Fetched AI config: call=%s ai_enabled=%s stt_enabled=%s product_code=%s has_bot=%s",
                        call_id, ai_config.get("ai_enabled"), ai_config.get("stt_enabled"),
                        ai_config.get("product_code"), bool(ai_config.get("bot")))

        ai_config = _apply_test_overrides(ai_config, test_overrides)
        ctx = _build_context(call_id, tenant_id, product_code, direction, ai_config)
        active_calls[call_id] = ctx
        logger.info("Context built: call=%s bot_mode=%s transcript_only=%s agent_assist=%s stt=%s tts=%s llm=%s rvc=%s",
                    call_id, ctx.is_bot_mode, ctx.is_transcript_only, ctx.is_agent_assist_mode,
                    ctx.providers.stt_provider, ctx.providers.tts_provider, ctx.providers.llm_provider,
                    ctx.providers.rvc_enabled)

        logger.info("Building pipeline: call=%s", call_id)
        task, runner = build_pipeline(ctx, websocket)
        active_sessions[call_id] = ActiveCallSession(ctx=ctx, task=task)
        logger.info("Pipeline built: call=%s mode=%s", call_id, ctx.product_code)
        if ctx.is_bot_mode and ctx.bot.greeting_message and not _uses_native_audio_llm(ctx):
            asyncio.create_task(_queue_initial_greeting(ctx, task))
        logger.info("Starting runner: call=%s", call_id)
        await runner.run(task)
        logger.warning("Runner returned: call=%s", call_id)

    except WebSocketDisconnect:
        logger.info("Disconnected: call=%s", call_id)
    except Exception as e:
        logger.error("Error: call=%s err=%s", call_id, e, exc_info=True)
    finally:
        active_calls.pop(call_id, None)
        active_sessions.pop(call_id, None)
        logger.info("Cleaned up: call=%s", call_id)


def _build_context(call_id, tenant_id, product_code, direction, ai_config) -> CallContext:
    bot_data = ai_config.get("bot", {})
    stt_provider = ai_config.get("stt_provider", settings.default_stt_provider)
    tts_provider = ai_config.get("tts_provider", bot_data.get("voice_provider", settings.default_tts_provider))
    llm_provider = ai_config.get("llm_provider", settings.default_llm_provider)
    stt_options = _resolve_provider_options(ai_config, bot_data, "stt", stt_provider)
    tts_options = _resolve_provider_options(ai_config, bot_data, "tts", tts_provider)
    llm_options = _resolve_provider_options(ai_config, bot_data, "llm", llm_provider)

    providers = ProviderConfig(
        stt_provider=stt_provider,
        stt_model=ai_config.get("stt_model"),
        stt_api_key=_resolve_api_key(ai_config, "stt_api_key", stt_options),
        stt_options=stt_options,
        tts_provider=tts_provider,
        tts_model=ai_config.get("tts_model"),
        tts_voice=ai_config.get("tts_voice"),
        tts_gender=ai_config.get("tts_gender"),
        tts_api_key=_resolve_api_key(ai_config, "tts_api_key", tts_options),
        tts_options=tts_options,
        llm_provider=llm_provider,
        llm_model=ai_config.get("llm_model"),
        llm_api_key=_resolve_api_key(ai_config, "llm_api_key", llm_options),
        llm_options=llm_options,
        rvc_enabled=ai_config.get("rvc_enabled", False),
        rvc_model_id=ai_config.get("rvc_model_id"),
    )

    # Escalation intents
    esc_raw = bot_data.get("escalation_intents", {})
    escalation_rules = {}
    if esc_raw:
        escalation_rules["intents"] = {n: float(c.get("threshold", 0.90)) for n, c in esc_raw.items()}
        escalation_rules["intent_actions"] = esc_raw

    # RAG: knowledge docs → system prompt
    base_prompt = bot_data.get("system_prompt", "You are a helpful assistant.")
    docs = bot_data.get("knowledge_documents", [])
    if docs:
        rag = "\n\n--- KNOWLEDGE BASE ---\n"
        for d in docs:
            rag += f"### [{d.get('content_type','INFO')}] {d.get('title','')}\n{d.get('content','')}\n\n"
        rag += "--- END KNOWLEDGE BASE ---\nAnswer from knowledge base when possible.\n"
        base_prompt += rag

    bot = BotConfig(
        bot_id=bot_data.get("bot_id"), name=bot_data.get("name", "Assistant"),
        system_prompt=base_prompt,
        greeting_message=bot_data.get("greeting_message", "Hello, how can I help you?"),
        goodbye_message=bot_data.get("goodbye_message", "Thank you. Goodbye!"),
        guidelines=bot_data.get("guidelines", []),
        allowed_intents=bot_data.get("allowed_intents", []),
        fallback_message=bot_data.get("fallback_message", "Could you repeat that?"),
        escalation_rules=escalation_rules,
        transfer_target=bot_data.get("transfer_target"),
        language=bot_data.get("language", "en"),
        max_turns=bot_data.get("max_turns", 30),
        max_duration_seconds=bot_data.get("max_duration_seconds", 600),
        dtmf_enabled=bot_data.get("dtmf_enabled", True),
        barge_in_enabled=bot_data.get("barge_in_enabled", True),
        sentiment_tracking=bot_data.get("sentiment_tracking", False),
        voice_provider=bot_data.get("voice_provider"),
        voice_id=bot_data.get("voice_id"),
        voice_gender=bot_data.get("voice_gender"),
        voice_speed=bot_data.get("voice_speed", 1.0),
    )

    ctx = CallContext(
        call_id=call_id, tenant_id=tenant_id,
        product_code=ai_config.get("product_code", product_code),
        ai_enabled=ai_config.get("ai_enabled", False),
        stt_enabled=ai_config.get("stt_enabled", True),
        sentiment_enabled=ai_config.get("sentiment_enabled", False) or bot.sentiment_tracking,
        agent_assist_enabled=ai_config.get("agent_assist_enabled", False),
        providers=providers, bot=bot,
        caller_number=ai_config.get("caller_number", ""),
        callee_number=ai_config.get("callee_number", ""),
        direction=direction,
    )

    # Attach campaign + contact context (used by build_system_prompt)
    ctx.campaign = ai_config.get("campaign")
    ctx.contact = ai_config.get("contact")
    logger.debug("Context payload: call=%s bot_name=%s language=%s allowed_intents=%s campaign=%s contact=%s",
                 call_id, bot.name, bot.language, bot.allowed_intents, bool(ctx.campaign), bool(ctx.contact))
    logger.debug(
        "Provider runtime config: call=%s stt=%s tts=%s llm=%s tts_voice=%s tts_gender=%s",
        call_id,
        providers.stt_provider,
        providers.tts_provider,
        providers.llm_provider,
        providers.tts_voice or bot.voice_id,
        providers.tts_gender or bot.voice_gender,
    )

    return ctx
