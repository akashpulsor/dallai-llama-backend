"""
Pipecat-native pipeline builder (v0.0.43).

Pipeline:
  Audio In → VAD → STT → [TranscriptRelay] → LLM → [IntentCheck] → TTS → [RVC] → [PostProcess] → Audio Out

RVC slot: if rvc_enabled, transforms TTS audio through voice model.
PostProcess: noise gate + normalization on all outgoing audio.
Campaign context: for outbound dialer, contact name/company injected into system prompt.
"""
import asyncio
import json
import time
import logging
import re
from datetime import datetime, timezone
from deepgram import LiveOptions
import aiohttp

from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.runner import PipelineRunner
from pipecat.pipeline.task import PipelineParams, PipelineTask
from pipecat.frames.frames import (
    Frame, TextFrame, TranscriptionFrame, InterimTranscriptionFrame, LLMTextFrame,
    LLMFullResponseStartFrame, LLMFullResponseEndFrame, EndFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.processors.aggregators.openai_llm_context import OpenAILLMContext
from pipecat.processors.aggregators.openai_llm_context import OpenAILLMContextFrame
from pipecat.services.openai import OpenAILLMService, OpenAITTSService
from pipecat.services.ollama.llm import OLLamaLLMService
from pipecat.services.deepgram import DeepgramSTTService, DeepgramTTSService
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.audio.vad.vad_analyzer import VADParams
from pipecat.transports.network.fastapi_websocket import FastAPIWebsocketTransport, FastAPIWebsocketParams

from pipeline.callbacks import pbx_core_client
from pipeline.processors.intent.detector import IntentTracker
from pipeline.processors.sentiment.analyzer import SentimentAnalyzer
from pipeline.processors.audio.input_noise_reducer import InputNoiseReducer
from pipeline.processors.audio.rvc_processor import RvcProcessor
from pipeline.processors.audio.post_processor import AudioPostProcessor
from pipeline.services.indic_tts import IndicHttpTTSService
from pipeline.serializers.raw_pcm import RawPCMSerializer
from models.call_context import CallContext
from config import settings
from db.analytics_store import store_event as persist_analytics_event

logger = logging.getLogger(__name__)


def _normalize_llm_json(raw: str) -> str:
    text = raw.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return text


def _extract_json_payload(raw: str) -> str:
    text = _normalize_llm_json(raw)
    fenced_match = re.search(r"```json\s*(\{.*?\})\s*```", text, flags=re.DOTALL | re.IGNORECASE)
    if fenced_match:
        candidate = fenced_match.group(1).strip()
        try:
            json.loads(candidate)
            return candidate
        except (json.JSONDecodeError, ValueError):
            pass
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        candidate = text[start : end + 1]
        try:
            json.loads(candidate)
            return candidate
        except (json.JSONDecodeError, ValueError):
            return text
    return text


def _normalize_text_for_compare(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"[^\w\s]", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _strip_emoji(text: str) -> str:
    return re.sub(r"[\U00010000-\U0010ffff]", "", text)


def _is_near_duplicate_reply(new_text: str, history: list[dict]) -> bool:
    new_norm = _normalize_text_for_compare(new_text)
    if not new_norm:
        return False
    recent_assistant = [m["content"] for m in history if m.get("role") == "assistant"][-2:]
    for prior in recent_assistant:
        prior_norm = _normalize_text_for_compare(prior)
        if not prior_norm:
            continue
        if new_norm == prior_norm or new_norm in prior_norm or prior_norm in new_norm:
            return True
    return False


def _store_event(call_id: str, event: dict):
    """Store event for test UI polling. No-op in production (test_console not imported)."""
    try:
        from api.test_console import add_event
        add_event(call_id, event)
    except Exception:
        pass


# ═══════════════════════════════════════════════════════════
# CUSTOM FRAME PROCESSORS
# ═══════════════════════════════════════════════════════════

class TranscriptRelayProcessor(FrameProcessor):
    def __init__(self, ctx: CallContext, sentiment: SentimentAnalyzer = None, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.sentiment = sentiment

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, TranscriptionFrame):
            logger.info("Transcript final: call=%s text=%s", self.ctx.call_id, frame.text[:200])
            logger.debug("STT final raw: call=%s result=%s", self.ctx.call_id, getattr(frame, "result", None))
            asyncio.create_task(
                persist_analytics_event(
                    call_id=self.ctx.call_id,
                    tenant_id=self.ctx.tenant_id,
                    product_code=self.ctx.product_code,
                    speaker="CUSTOMER",
                    event_type="transcript_final",
                    utterance=frame.text,
                    turn_index=self.ctx.turn_count,
                )
            )
            asyncio.create_task(pbx_core_client.send_live_transcript(
                call_id=self.ctx.call_id, tenant_id=self.ctx.tenant_id,
                speaker="CALLER", text=frame.text, is_final=True,
                confidence=1.0, timestamp_ms=int(time.time() * 1000)))
            # Store for test UI polling
            _store_event(self.ctx.call_id, {"type": "transcript_final", "text": frame.text})
            if self.sentiment:
                score, label = self.sentiment.analyze(frame.text)
                self.ctx.current_sentiment = score
                self.ctx.sentiment_history.append(score)
                asyncio.create_task(
                    persist_analytics_event(
                        call_id=self.ctx.call_id,
                        tenant_id=self.ctx.tenant_id,
                        product_code=self.ctx.product_code,
                        speaker="CUSTOMER",
                        event_type="sentiment",
                        utterance=frame.text,
                        tone_label=label,
                        tone_score=score,
                        turn_index=self.ctx.turn_count,
                    )
                )
                asyncio.create_task(pbx_core_client.send_sentiment(
                    call_id=self.ctx.call_id, tenant_id=self.ctx.tenant_id,
                    score=score, label=label, emotion_history=self.sentiment.history))
                _store_event(self.ctx.call_id, {"type": "sentiment", "score": score, "label": label})
        elif isinstance(frame, InterimTranscriptionFrame):
            logger.debug("Transcript partial: call=%s text=%s", self.ctx.call_id, frame.text[:200])
            logger.debug("STT partial raw: call=%s result=%s", self.ctx.call_id, getattr(frame, "result", None))
            asyncio.create_task(pbx_core_client.send_live_transcript(
                call_id=self.ctx.call_id, tenant_id=self.ctx.tenant_id,
                speaker="CALLER", text=frame.text, is_final=False,
                confidence=0.5, timestamp_ms=int(time.time() * 1000)))
            _store_event(self.ctx.call_id, {"type": "transcript_partial", "text": frame.text})
        await self.push_frame(frame, direction)


class UserLLMTriggerProcessor(FrameProcessor):
    """Convert final STT transcriptions into direct LLM context frames."""

    def __init__(self, ctx: CallContext, context: OpenAILLMContext, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.context = context

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)

        if isinstance(frame, TranscriptionFrame):
            text = frame.text.strip()
            if not text:
                return
            self.ctx.add_conversation_turn("user", text)
            self.context.add_message({"role": "user", "content": text})
            while len(self.context.messages) > 8:
                self.context.messages.pop(1)
            logger.info("Triggering LLM from transcript: call=%s text=%s", self.ctx.call_id, text[:200])
            await self.push_frame(OpenAILLMContextFrame(self.context), direction)
            return

        if isinstance(frame, InterimTranscriptionFrame):
            return

        await self.push_frame(frame, direction)


class IntentCheckProcessor(FrameProcessor):
    def __init__(self, ctx: CallContext, tracker: IntentTracker, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.tracker = tracker
        self._buf = ""
        self._collecting = False

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, LLMFullResponseStartFrame):
            logger.info("LLM response started: call=%s", self.ctx.call_id)
            self._buf = ""
            self._collecting = True
            return
        if isinstance(frame, (LLMTextFrame, TextFrame)) and self._collecting:
            logger.info("LLM chunk: call=%s chunk=%s", self.ctx.call_id, frame.text[:200])
            logger.debug("LLM text chunk: call=%s chunk=%s", self.ctx.call_id, frame.text[:200])
            self._buf += frame.text
            return
        if isinstance(frame, LLMFullResponseEndFrame):
            logger.info("LLM response ended: call=%s chars=%s", self.ctx.call_id, len(self._buf))
            self._collecting = False
            await self._process()
            return
        await self.push_frame(frame, direction)

    async def _process(self):
        raw = _extract_json_payload(self._buf)
        logger.info("LLM raw response: call=%s raw=%s", self.ctx.call_id, raw[:500])
        resp, intent, conf, esc, esc_reason, tone_label = raw, "unknown", 0.0, False, None, None
        try:
            p = json.loads(raw)
            resp = p.get("response", raw)
            intent = p.get("intent", "unknown")
            conf = float(p.get("intent_confidence", 0.0))
            tone_label = p.get("sentiment_hint")
            esc = bool(p.get("escalate", False))
            esc_reason = p.get("escalate_reason")
        except (json.JSONDecodeError, ValueError):
            pass
        resp = _strip_emoji(resp).strip()
        if intent == "greeting" and self.ctx.turn_count > 0:
            resp = "Haan ji, main sun rahi hoon. Aapko phone system mein exactly kya chahiye?"
        if _is_near_duplicate_reply(resp, self.ctx.conversation_history):
            logger.warning(
                "Suppressing near-duplicate assistant reply: call=%s response=%s",
                self.ctx.call_id,
                resp[:200],
            )
            resp = self.ctx.bot.fallback_message or "Ji, aap apni exact requirement batayein."
        logger.info("LLM parsed: call=%s intent=%s conf=%.3f escalate=%s response=%s",
                    self.ctx.call_id, intent, conf, esc, resp[:200])

        utt = self.ctx.conversation_history[-1]["content"] if self.ctx.conversation_history else ""
        self.tracker.record(utterance=utt, intent=intent, confidence=conf, speaker="CALLER")
        self.ctx.add_intent(utt, intent, conf, "CALLER")
        self.ctx.add_conversation_turn("assistant", resp)
        asyncio.create_task(
            persist_analytics_event(
                call_id=self.ctx.call_id,
                tenant_id=self.ctx.tenant_id,
                product_code=self.ctx.product_code,
                speaker="BOT",
                event_type="bot_response",
                utterance=resp,
                intent=intent,
                intent_confidence=conf,
                tone_label=tone_label,
                tone_score=self.ctx.current_sentiment if tone_label else None,
                turn_index=self.ctx.turn_count,
                metadata_json={"raw_response": raw},
            )
        )

        # Store for test UI polling
        _store_event(self.ctx.call_id, {
            "type": "bot_response", "text": resp,
            "intent": intent, "intent_confidence": conf,
        })

        if esc:
            if resp: await self.push_frame(TextFrame(text=resp))
            await self._escalate(esc_reason or "llm_triggered")
            return
        tr = self.tracker.check_escalation_threshold()
        if tr:
            a, t, r = tr
            if resp: await self.push_frame(TextFrame(text=resp))
            await self._escalate(r, a, t)
            return
        if self.ctx.turn_count >= self.ctx.bot.max_turns:
            await self._escalate("max_turns_reached")
            return
        elapsed_seconds = (datetime.now(timezone.utc) - self.ctx.started_at).total_seconds()
        if elapsed_seconds >= self.ctx.bot.max_duration_seconds:
            await self._escalate("max_duration_reached")
            return
        if resp:
            await self.push_frame(TextFrame(text=resp))

    async def _escalate(self, reason, action=None, target=None):
        a = action or "TRANSFER_QUEUE"
        t = target or self.ctx.bot.transfer_target or "default"
        s = self.tracker.build_summary()
        d, _ = self.tracker.get_dominant_intent()
        escalation_message = (
            f"I am escalating you to an agent now. Please stay on the line."
            if self.ctx.bot.language.startswith("en")
            else "Main aapko ab agent se connect kar rahi hoon. Kripya line par bane rahiye."
        )
        logger.warning("Escalating call: call=%s action=%s target=%s reason=%s dominant_intent=%s",
                       self.ctx.call_id, a, t, reason, d)
        asyncio.create_task(pbx_core_client.escalate(
            call_id=self.ctx.call_id, tenant_id=self.ctx.tenant_id,
            escalation_type=a, target=t, reason=reason,
            transcript_summary=s, sentiment_score=self.ctx.current_sentiment, intent=d))
        asyncio.create_task(
            persist_analytics_event(
                call_id=self.ctx.call_id,
                tenant_id=self.ctx.tenant_id,
                product_code=self.ctx.product_code,
                speaker="SYSTEM",
                event_type="escalation",
                utterance=escalation_message,
                intent=d,
                tone_score=self.ctx.current_sentiment,
                metadata_json={"action": a, "target": t, "reason": reason},
                turn_index=self.ctx.turn_count,
            )
        )
        _store_event(
            self.ctx.call_id,
            {
                "type": "escalation",
                "action": a,
                "target": t,
                "reason": reason,
                "message": escalation_message,
            },
        )
        await self.push_frame(TextFrame(text=escalation_message))
        await self.push_frame(EndFrame())


class CallEndProcessor(FrameProcessor):
    def __init__(self, ctx: CallContext, tracker: IntentTracker, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.tracker = tracker

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, EndFrame):
            logger.warning("EndFrame observed: call=%s turns=%s", self.ctx.call_id, self.ctx.turn_count)
            s = self.tracker.build_summary()
            m = (datetime.now(timezone.utc) - self.ctx.started_at).total_seconds() / 60.0
            asyncio.create_task(pbx_core_client.send_final_transcript(
                call_id=self.ctx.call_id, transcript_summary=s,
                ai_minutes=round(m, 2), total_turns=self.ctx.turn_count))
        await self.push_frame(frame, direction)


# ═══════════════════════════════════════════════════════════
# SYSTEM PROMPT BUILDER
# ═══════════════════════════════════════════════════════════

INTENT_SUFFIX = """

IMPORTANT: Respond in valid JSON only:
{"response":"your reply","intent":"caller intent","intent_confidence":0.0-1.0,"sentiment_hint":"positive|neutral|negative","escalate":false,"escalate_reason":null}
Set escalate=true if caller wants human agent or you cannot help. JSON only.

Response rules:
- Never use markdown, code fences, or backticks.
- Never output the JSON inside json fences.
- Keep the spoken response short: usually 1-2 sentences, max 35 words unless the user explicitly asks for detail.
- Sound natural and human.
- Use simple Indian Hinglish with clean pronunciation-friendly wording.
- After the first bot greeting, do not greet again.
- If the caller only says hello/hi after your greeting, briefly acknowledge and ask one business question.
- Do not use jokes, slang, or playful expressions.
- Do not repeat the same point twice in one reply.
- Do not repeat your previous reply unless the user explicitly asks you to repeat.
- Do not mirror the user's sentence back to them unless clarification is needed.
- No emoji.
"""


def build_system_prompt(ctx: CallContext) -> str:
    """
    Build complete system prompt with:
      1. Bot's base system prompt
      2. Campaign context (outbound dialer: contact name, company, script)
      3. RAG knowledge docs (injected into context)
      4. Allowed intents
      5. Intent JSON suffix
    """
    prompt = ctx.bot.system_prompt

    # Campaign context: personalize for outbound calls
    if hasattr(ctx, 'campaign') and ctx.campaign:
        prompt += f"\n\nCAMPAIGN: {ctx.campaign.get('campaign_name', 'Unknown')}"
        prompt += f"\nType: {ctx.campaign.get('campaign_type', 'OUTBOUND')}"

    if hasattr(ctx, 'contact') and ctx.contact:
        prompt += "\n\nYou are calling this person:"
        if ctx.contact.get('name'):
            prompt += f"\nName: {ctx.contact['name']}"
        if ctx.contact.get('company'):
            prompt += f"\nCompany: {ctx.contact['company']}"
        if ctx.contact.get('custom_data'):
            for k, v in ctx.contact['custom_data'].items():
                prompt += f"\n{k}: {v}"
        attempt = ctx.contact.get('attempt_count', 0)
        if attempt > 0:
            prompt += f"\nThis is attempt #{attempt + 1}. Previous disposition: {ctx.contact.get('disposition', 'no answer')}"
        prompt += "\n\nUse this information to personalize the conversation. Address them by name."

    # Intent suffix (forces JSON response with intent detection)
    prompt += INTENT_SUFFIX

    if ctx.bot.allowed_intents:
        prompt += f"\nPreferred intents: {', '.join(ctx.bot.allowed_intents)}"

    return prompt


# ═══════════════════════════════════════════════════════════
# PIPELINE BUILDER
# ═══════════════════════════════════════════════════════════

def build_pipeline(ctx: CallContext, websocket) -> tuple[PipelineTask, PipelineRunner]:
    openai_key = ctx.providers.llm_api_key or settings.openai_api_key
    deepgram_key = ctx.providers.stt_api_key or settings.deepgram_api_key
    aiohttp_session = None

    # VAD
    logger.info("Initializing VAD: call=%s sample_rate=%s", ctx.call_id, 16000)
    vad = SileroVADAnalyzer(sample_rate=16000, params=VADParams(stop_secs=0.8))

    # Transport
    tts_sample_rate = 24000 if ctx.providers.tts_provider == "openai" else 16000
    logger.info("Initializing transport: call=%s audio_in=%s audio_out=%s tts_sample_rate=%s",
                ctx.call_id, True, ctx.is_bot_mode, tts_sample_rate)
    transport = FastAPIWebsocketTransport(
        websocket=websocket,
        params=FastAPIWebsocketParams(
            audio_in_enabled=True,
            audio_out_enabled=ctx.is_bot_mode,
            audio_in_sample_rate=16000,
            audio_out_sample_rate=tts_sample_rate,
            vad_enabled=True,
            vad_analyzer=vad,
            serializer=RawPCMSerializer(
                RawPCMSerializer.InputParams(
                    audio_in_sample_rate=16000,
                    audio_out_sample_rate=tts_sample_rate,
                    num_channels=1,
                )
            ),
        ),
    )

    # STT
    input_noise_reducer = InputNoiseReducer(
        sample_rate=16000,
        highpass_hz=settings.audio_highpass_hz,
        enabled=True,
    )
    logger.info("Input noise reducer enabled: call=%s highpass_hz=%s", ctx.call_id, settings.audio_highpass_hz)

    logger.info("Initializing STT: call=%s provider=%s model=%s language=%s",
                ctx.call_id, ctx.providers.stt_provider, ctx.providers.stt_model or settings.deepgram_stt_model,
                ctx.bot.language)
    stt = DeepgramSTTService(
        api_key=deepgram_key,
        live_options=LiveOptions(
            model=ctx.providers.stt_model or settings.deepgram_stt_model,
            language=ctx.bot.language,
            interim_results=True,
            smart_format=True,
            vad_events=True,
            sample_rate=16000,
            encoding="linear16",
            channels=1,
        ),
        sample_rate=16000,
        should_interrupt=False,
    )

    # LLM
    logger.info("Initializing LLM: call=%s provider=%s model=%s", ctx.call_id, ctx.providers.llm_provider,
                ctx.providers.llm_model or settings.openai_llm_model)
    llm_provider = (ctx.providers.llm_provider or settings.default_llm_provider or "openai").lower()
    if llm_provider == "ollama":
        llm_model = ctx.providers.llm_model or settings.ollama_llm_model
        logger.info("Using Ollama LLM: call=%s model=%s base_url=%s", ctx.call_id, llm_model, settings.ollama_base_url)
        llm = OLLamaLLMService(model=llm_model, base_url=settings.ollama_base_url)
    else:
        llm_model = ctx.providers.llm_model or settings.openai_llm_model
        llm = OpenAILLMService(api_key=openai_key, model=llm_model)

    # TTS
    if ctx.providers.tts_provider == "indic_tts":
        aiohttp_session = aiohttp.ClientSession()
        logger.info(
            "Initializing TTS: call=%s provider=indic_tts url=%s voice=%s emotion=%s",
            ctx.call_id,
            settings.indic_tts_base_url,
            ctx.providers.tts_voice or settings.indic_tts_voice,
            settings.indic_tts_emotion,
        )
        tts = IndicHttpTTSService(
            base_url=settings.indic_tts_base_url,
            aiohttp_session=aiohttp_session,
            voice=ctx.providers.tts_voice or settings.indic_tts_voice,
            language=ctx.bot.language,
            emotion=settings.indic_tts_emotion,
            sample_rate=settings.indic_tts_sample_rate,
        )
        active_tts_provider = "indic_tts"
    elif ctx.providers.tts_provider == "deepgram" and deepgram_key:
        logger.info("Initializing TTS: call=%s provider=deepgram voice=%s", ctx.call_id,
                    ctx.providers.tts_voice or settings.deepgram_tts_model)
        tts = DeepgramTTSService(api_key=deepgram_key, voice=ctx.providers.tts_voice or settings.deepgram_tts_model)
        active_tts_provider = "deepgram"
    else:
        logger.info("Initializing TTS: call=%s provider=openai model=%s voice=%s", ctx.call_id,
                    ctx.providers.tts_model or settings.openai_tts_model,
                    ctx.providers.tts_voice or settings.openai_tts_voice)
        tts = OpenAITTSService(
            api_key=ctx.providers.tts_api_key or openai_key,
            model=ctx.providers.tts_model or settings.openai_tts_model,
            voice=ctx.providers.tts_voice or settings.openai_tts_voice,
        )
        # Pipecat 0.0.104 leaves TTSSettings.language as NOT_GIVEN for OpenAI TTS.
        # Set it explicitly to satisfy validate_complete() at service start.
        tts._settings.language = ctx.bot.language or None
        active_tts_provider = "openai"

    # LLM context
    system_prompt = build_system_prompt(ctx)
    logger.debug("System prompt built: call=%s length=%s", ctx.call_id, len(system_prompt))
    messages = [{"role": "system", "content": system_prompt}]
    context = OpenAILLMContext(messages=messages)
    context_aggregator = llm.create_context_aggregator(context)

    # Custom processors
    esc_intents, esc_actions = _parse_escalation(ctx)
    tracker = IntentTracker(escalation_intents=esc_intents, intent_actions=esc_actions)
    sentiment = SentimentAnalyzer() if ctx.sentiment_enabled else None
    transcript_relay = TranscriptRelayProcessor(ctx, sentiment)
    user_trigger = UserLLMTriggerProcessor(ctx, context)
    intent_check = IntentCheckProcessor(ctx, tracker)
    call_end = CallEndProcessor(ctx, tracker)

    # Build processor chain
    processors = [
        transport.input(),
        input_noise_reducer,
        stt,
        transcript_relay,
        user_trigger,
        llm,
        intent_check,
        tts,
    ]
    logger.info("Base processors ready: call=%s count=%s", ctx.call_id, len(processors))

    # RVC (voice conversion — optional, after TTS)
    if ctx.providers.rvc_enabled:
        rvc = RvcProcessor(
            server_url=settings.rvc_server_url,
            model_id=ctx.providers.rvc_model_id or "default",
            sample_rate=tts_sample_rate,
            enabled=True,
        )
        processors.append(rvc)
        logger.info("RVC enabled: model=%s server=%s", ctx.providers.rvc_model_id, settings.rvc_server_url)

    # Audio post-processing (noise gate + normalization — always on)
    post_process_enabled = active_tts_provider not in {"deepgram"}
    processors.append(AudioPostProcessor(enabled=post_process_enabled))
    logger.info("Audio post processor enabled: call=%s enabled=%s", ctx.call_id, post_process_enabled)

    # Output + context save + call end
    processors.extend([
        transport.output(),
        context_aggregator.assistant(),
        call_end,
    ])

    pipeline = Pipeline(processors)
    params = PipelineParams(allow_interruptions=ctx.bot.barge_in_enabled, enable_metrics=True)
    task = PipelineTask(pipeline, params=params)
    runner = PipelineRunner()

    logger.info("Pipeline: call=%s stt=%s llm=%s tts=%s rvc=%s barge=%s intents=%s processors=%s",
                 ctx.call_id, ctx.providers.stt_provider, ctx.providers.llm_provider,
                 ctx.providers.tts_provider, ctx.providers.rvc_enabled,
                 ctx.bot.barge_in_enabled, list(esc_intents.keys()), len(processors))
    logger.info(
        "Runtime path: call=%s llm_provider=%s llm_model=%s tts_provider=%s",
        ctx.call_id,
        llm_provider,
        llm_model,
        active_tts_provider,
    )

    return task, runner


def _parse_escalation(ctx: CallContext) -> tuple[dict, dict]:
    rules = ctx.bot.escalation_rules
    if not rules or not isinstance(rules, dict):
        return {}, {}
    return rules.get("intents", {}), rules.get("intent_actions", {})
