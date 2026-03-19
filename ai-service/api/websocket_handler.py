"""WebSocket handler — FreeSWITCH mod_audio_stream connects here."""
import asyncio
import logging
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


def _store_test_event(call_id: str, event: dict):
    try:
        from api.test_console import add_event
        add_event(call_id, event)
    except Exception:
        pass


async def handle_audio_websocket(
    websocket: WebSocket, call_id: str,
    tenant_id: str = "", product_code: str = "BASIC_PBX",
    bot_id: str = None, direction: str = "INBOUND",
    campaign_id: str = None, contact_id: str = None,
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
            # Fallback for test mode (no PBX-Core running)
            ai_config = {
                "ai_enabled": True, "stt_enabled": True, "sentiment_enabled": True,
                "product_code": product_code,
                "llm_provider": "ollama",
                "llm_model": settings.ollama_llm_model,
                "tts_provider": "deepgram",
                "tts_voice": settings.deepgram_tts_model,
                "bot": {
                    "name": "Dalai LLAMA Test Bot",
                    "system_prompt": (
                        "You are a helpful sales assistant for Dalai LLAMA, an AI-powered cloud communication platform. "
                        "You speak Hinglish (mix of Hindi and English) naturally, like a friendly Indian salesperson. "
                        "Use Hindi words naturally mixed with English. For example: "
                        "'Haan ji, main aapki help kar sakti hoon. Aapko kya chahiye?' "
                        "'Hamara Basic plan sirf 999 rupaye per month hai, ismein 5 agents milte hain.' "
                        "Be warm, professional, and helpful. Ask about their current phone system needs."
                    ),
                    "greeting_message": "Namaste! Main Priya hoon Dalai LLAMA se. Aaj main aapki kaise help kar sakti hoon?",
                    "goodbye_message": "Dhanyavaad aapka! Aapka din shubh ho!",
                    "language": "hi",
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
        if ctx.is_bot_mode and ctx.bot.greeting_message:
            logger.info("Queueing initial greeting: call=%s text=%s", call_id, ctx.bot.greeting_message[:200])
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
            _store_test_event(call_id, {"type": "bot_response", "text": ctx.bot.greeting_message, "intent": "greeting", "intent_confidence": 1.0})
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
    providers = ProviderConfig(
        stt_provider=ai_config.get("stt_provider", settings.default_stt_provider),
        stt_model=ai_config.get("stt_model"),
        stt_api_key=ai_config.get("stt_api_key"),
        tts_provider=ai_config.get("tts_provider", settings.default_tts_provider),
        tts_model=ai_config.get("tts_model"),
        tts_voice=ai_config.get("tts_voice", settings.openai_tts_voice),
        tts_api_key=ai_config.get("tts_api_key"),
        llm_provider=ai_config.get("llm_provider", settings.default_llm_provider),
        llm_model=ai_config.get("llm_model"),
        llm_api_key=ai_config.get("llm_api_key"),
        rvc_enabled=ai_config.get("rvc_enabled", False),
        rvc_model_id=ai_config.get("rvc_model_id"),
    )

    bot_data = ai_config.get("bot", {})

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

    return ctx
