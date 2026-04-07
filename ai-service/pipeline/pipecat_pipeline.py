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
    LLMFullResponseStartFrame, LLMFullResponseEndFrame, EndFrame, TTSSpeakFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.processors.aggregators.llm_context import LLMContext
from pipecat.processors.aggregators.llm_response_universal import LLMContextAggregatorPair
from pipecat.processors.aggregators.openai_llm_context import OpenAILLMContext
from pipecat.processors.aggregators.openai_llm_context import OpenAILLMContextFrame
from pipecat.services.openai import OpenAILLMService, OpenAITTSService
from pipecat.services.openai.stt import OpenAIRealtimeSTTService
from pipecat.services.ollama.llm import OLLamaLLMService
from pipecat.services.deepgram import DeepgramSTTService, DeepgramTTSService
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.audio.vad.vad_analyzer import VADParams
from pipecat.transports.network.fastapi_websocket import FastAPIWebsocketTransport, FastAPIWebsocketParams
from pipecat.transcriptions.language import Language

from pipeline.callbacks import pbx_core_client
from pipeline.processors.intent.detector import IntentTracker
from pipeline.processors.sentiment.analyzer import SentimentAnalyzer
from pipeline.processors.audio.input_noise_reducer import InputNoiseReducer
from pipeline.processors.audio.rvc_processor import RvcProcessor
from pipeline.processors.audio.post_processor import AudioPostProcessor
from pipeline.serializers.raw_pcm import RawPCMSerializer
from models.call_context import CallContext
from config import settings
from db.analytics_store import store_event as persist_analytics_event

logger = logging.getLogger(__name__)

TRANSCRIPT_INTENT_SUFFIX = """
Classify the caller's latest transcript into JSON only:
{"intent":"greeting|goodbye|interested|not_interested|pricing|technical_support|demo_request|callback_request|human_agent|feature_question|product_comparison|unknown","intent_confidence":0.0-1.0,"sentiment_hint":"positive|neutral|negative"}
Return JSON only. No markdown. No explanation.
"""


class ProviderSetupError(RuntimeError):
    def __init__(self, provider_type: str, provider_name: str, reason: str):
        self.provider_type = provider_type
        self.provider_name = provider_name
        self.reason = reason
        super().__init__(f"{provider_type.upper()} provider '{provider_name}' setup failed: {reason}")


def _require_value(value: str | None, provider_type: str, provider_name: str, field_name: str) -> None:
    if value not in (None, ""):
        return
    raise ProviderSetupError(provider_type, provider_name, f"missing required credential/config '{field_name}'")


def _has_any(*values: str | None) -> bool:
    return any(value not in (None, "") for value in values)


def _validate_provider_inputs(ctx: CallContext) -> None:
    stt_provider = (ctx.providers.stt_provider or settings.default_stt_provider or "deepgram").lower()
    llm_provider = (ctx.providers.llm_provider or settings.default_llm_provider or "openai").lower()
    tts_provider = (ctx.providers.tts_provider or settings.default_tts_provider or "openai").lower()

    if stt_provider == "deepgram":
        _require_value(ctx.providers.stt_api_key or ctx.providers.stt_options.get("api_key") or settings.deepgram_api_key, "stt", stt_provider, "api_key")
    elif stt_provider == "openai":
        _require_value(ctx.providers.stt_api_key or ctx.providers.stt_options.get("api_key") or ctx.providers.llm_api_key or settings.openai_api_key, "stt", stt_provider, "api_key")
    elif stt_provider == "google":
        if not _has_any(
            ctx.providers.stt_options.get("credentials_json"),
            ctx.providers.stt_options.get("credentials_path"),
            settings.google_credentials_json,
            settings.google_credentials_path,
        ):
            raise ProviderSetupError("stt", stt_provider, "missing Google credentials_json or credentials_path")

    if llm_provider == "openai":
        _require_value(ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.openai_api_key, "llm", llm_provider, "api_key")
    elif llm_provider == "google":
        _require_value(ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.google_api_key, "llm", llm_provider, "api_key")
    elif llm_provider == "gemini_live":
        _require_value(ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.google_api_key, "llm", llm_provider, "api_key")
    elif llm_provider == "vertex":
        _require_value(ctx.providers.llm_options.get("project_id") or settings.google_project_id, "llm", llm_provider, "project_id")
        if not _has_any(
            ctx.providers.llm_options.get("credentials_json"),
            ctx.providers.llm_options.get("credentials_path"),
            settings.google_credentials_json,
            settings.google_credentials_path,
        ):
            raise ProviderSetupError("llm", llm_provider, "missing Vertex credentials_json or credentials_path")
    elif llm_provider == "ollama":
        _require_value(ctx.providers.llm_options.get("base_url") or settings.ollama_base_url, "llm", llm_provider, "base_url")

    if llm_provider != "gemini_live":
        if tts_provider == "deepgram":
            _require_value(ctx.providers.tts_api_key or ctx.providers.tts_options.get("api_key") or settings.deepgram_api_key, "tts", tts_provider, "api_key")
        elif tts_provider == "openai":
            _require_value(ctx.providers.tts_api_key or ctx.providers.tts_options.get("api_key") or ctx.providers.llm_api_key or settings.openai_api_key, "tts", tts_provider, "api_key")
        elif tts_provider == "google":
            if not _has_any(
                ctx.providers.tts_options.get("credentials_json"),
                ctx.providers.tts_options.get("credentials_path"),
                settings.google_credentials_json,
                settings.google_credentials_path,
            ):
                raise ProviderSetupError("tts", tts_provider, "missing Google credentials_json or credentials_path")


def _provider_error_reason(exc: Exception) -> str:
    text = str(exc).strip() or exc.__class__.__name__
    lowered = text.lower()
    if "429" in lowered or "rate limit" in lowered or "quota" in lowered:
        return f"rate limited by upstream provider: {text}"
    if "401" in lowered or "403" in lowered or "unauthorized" in lowered or "forbidden" in lowered or "invalid api key" in lowered:
        return f"authentication/authorization failure from upstream provider: {text}"
    if "timeout" in lowered:
        return f"timeout while connecting to upstream provider: {text}"
    if "dns" in lowered or "connection" in lowered or "network" in lowered or "temporarily unavailable" in lowered:
        return f"network/connectivity failure to upstream provider: {text}"
    return text


def _google_credentials_kwargs(overrides: dict | None = None) -> dict:
    overrides = overrides or {}
    kwargs: dict = {
        "project_id": overrides.get("project_id", settings.google_project_id),
        "location": overrides.get("location", settings.google_location),
    }
    credentials_json = overrides.get("credentials_json") or overrides.get("credentials")
    credentials_path = overrides.get("credentials_path")
    if credentials_json:
        kwargs["credentials"] = credentials_json
    elif settings.google_credentials_json:
        kwargs["credentials"] = settings.google_credentials_json
    if credentials_path:
        kwargs["credentials_path"] = credentials_path
    elif settings.google_credentials_path:
        kwargs["credentials_path"] = settings.google_credentials_path
    return kwargs


def _google_tts_credentials_kwargs(overrides: dict | None = None) -> dict:
    overrides = overrides or {}
    kwargs: dict = {}
    credentials_json = overrides.get("credentials_json") or overrides.get("credentials")
    credentials_path = overrides.get("credentials_path")
    if credentials_json:
        kwargs["credentials"] = credentials_json
    elif settings.google_credentials_json:
        kwargs["credentials"] = settings.google_credentials_json
    if credentials_path:
        kwargs["credentials_path"] = credentials_path
    elif settings.google_credentials_path:
        kwargs["credentials_path"] = settings.google_credentials_path
    return kwargs


def _google_stt_credentials_kwargs(overrides: dict | None = None) -> dict:
    overrides = overrides or {}
    kwargs: dict = {
        "location": overrides.get("location") or settings.google_location or "global",
    }
    credentials_json = overrides.get("credentials_json") or overrides.get("credentials")
    credentials_path = overrides.get("credentials_path")
    if credentials_json:
        kwargs["credentials"] = credentials_json
    elif settings.google_credentials_json:
        kwargs["credentials"] = settings.google_credentials_json
    if credentials_path:
        kwargs["credentials_path"] = credentials_path
    elif settings.google_credentials_path:
        kwargs["credentials_path"] = settings.google_credentials_path
    return kwargs


def _is_native_audio_llm_provider(provider_name: str) -> bool:
    return (provider_name or "").lower() == "gemini_live"


def _load_google_services():
    try:
        from pipecat.services.google.llm import GoogleLLMService
        from pipecat.services.google.llm_vertex import GoogleVertexLLMService
        from pipecat.services.google.gemini_live import GeminiLiveLLMService
        from pipecat.services.google.stt import GoogleSTTService
        from pipecat.services.google.tts import GoogleTTSService, GeminiTTSService
    except Exception as exc:
        raise RuntimeError(
            "Google provider initialization failed. Pipecat's Google package imports STT modules during package load, so the runtime must include "
            "google-genai, google-cloud-texttospeech, google-cloud-speech, and google-auth. "
            f"Original error: {exc}"
        ) from exc
    return GoogleLLMService, GoogleTTSService, GoogleVertexLLMService, GeminiTTSService, GoogleSTTService, GeminiLiveLLMService


def _resolve_tts_voice(ctx: CallContext, provider_name: str) -> str:
    if ctx.bot.voice_id:
        return ctx.bot.voice_id
    if ctx.providers.tts_voice:
        return ctx.providers.tts_voice

    gender = (ctx.bot.voice_gender or ctx.providers.tts_gender or "").strip().lower()
    if provider_name == "google":
        if gender == "male":
            return settings.google_tts_voice_male
        if gender == "female":
            return settings.google_tts_voice_female
        return settings.google_tts_voice
    return ctx.providers.tts_voice or settings.openai_tts_voice


def _resolve_tts_sample_rate(ctx: CallContext, provider_name: str) -> int:
    configured = ctx.providers.tts_options.get("sample_rate")
    if configured not in (None, ""):
        try:
            return int(configured)
        except (TypeError, ValueError):
            logger.warning("Invalid TTS sample rate override: call=%s value=%s", ctx.call_id, configured)
    if provider_name == "gemini_live":
        return 16000
    if provider_name in {"openai", "google", "deepgram"}:
        return 24000
    return 16000


def _resolve_native_audio_voice(ctx: CallContext) -> str:
    if (ctx.bot.voice_provider or "").lower() == "gemini_live" and ctx.bot.voice_id:
        return ctx.bot.voice_id
    return (
        ctx.providers.llm_options.get("voice_id")
        or ctx.bot.voice_id
        or ctx.providers.tts_voice
        or settings.google_gemini_live_voice
    )


def _resolve_stt_language(ctx: CallContext) -> str:
    provider_name = (ctx.providers.stt_provider or settings.default_stt_provider or "").lower()
    language = (ctx.bot.language or "en").strip()
    if provider_name == "deepgram":
        mapping = {
            "hi-IN": "hi",
            "en-IN": "en",
            "en-US": "en-US",
        }
        return mapping.get(language, language)
    return language


def _resolve_google_stt_language(ctx: CallContext) -> Language:
    language = (_resolve_stt_language(ctx) or "en-US").strip()
    try:
        return Language(language)
    except ValueError:
        base_code = language.split("-", 1)[0].lower()
        fallback_map = {
            "hi": Language.HI,
            "en": Language.EN_US,
        }
        return fallback_map.get(base_code, Language.EN_US)


def _resolve_openai_stt_language(ctx: CallContext) -> Language:
    language = (_resolve_stt_language(ctx) or "en-US").strip()
    try:
        return Language(language)
    except ValueError:
        base_code = language.split("-", 1)[0].lower()
        fallback_map = {
            "hi": Language.HI,
            "en": Language.EN_US,
        }
        return fallback_map.get(base_code, Language.EN_US)


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


def _extract_response_from_json_like_text(raw: str) -> str | None:
    match = re.search(r'"response"\s*:\s*"((?:[^"\\]|\\.)*)"', raw, flags=re.DOTALL)
    if not match:
        return None
    value = match.group(1)
    value = value.replace('\\"', '"').replace("\\n", " ").replace("\\t", " ")
    return value.strip()


def _extract_intent_json(raw: str) -> tuple[str, float, str]:
    fallback_intent = _infer_intent_from_user_text(raw, "unknown")
    fallback = (fallback_intent, 0.55 if fallback_intent != "unknown" else 0.0, "neutral")
    if not raw:
        return fallback
    payload = _extract_json_payload(raw)
    try:
        parsed = json.loads(payload)
        intent = str(parsed.get("intent", "unknown")).strip() or "unknown"
        confidence = float(parsed.get("intent_confidence", 0.0))
        sentiment_hint = str(parsed.get("sentiment_hint", "neutral")).strip() or "neutral"
        return intent, confidence, sentiment_hint
    except (json.JSONDecodeError, TypeError, ValueError, AttributeError):
        return fallback


def _gender_value(ctx: CallContext) -> str:
    return (ctx.bot.voice_gender or ctx.providers.tts_gender or "").strip().lower()


def _is_male_voice(ctx: CallContext) -> bool:
    return _gender_value(ctx) == "male"


def _listening_phrase(ctx: CallContext) -> str:
    return "main sun raha hoon" if _is_male_voice(ctx) else "main sun rahi hoon"


def _help_phrase(ctx: CallContext) -> str:
    return "main help karta hoon" if _is_male_voice(ctx) else "main help karti hoon"


def _intent_classifier_prompt(ctx: CallContext, transcript: str) -> list[dict]:
    allowed = ctx.bot.allowed_intents or [
        "greeting",
        "goodbye",
        "interested",
        "not_interested",
        "pricing",
        "technical_support",
        "demo_request",
        "callback_request",
        "human_agent",
        "feature_question",
        "product_comparison",
        "unknown",
    ]
    system = (
        "You classify live caller transcripts for a telephony assistant. "
        "Pick the closest intent from this list only: "
        f"{', '.join(allowed)}.\n"
        "Use the transcript exactly as heard, including mixed Hindi-English telephony language.\n"
        f"{TRANSCRIPT_INTENT_SUFFIX}"
    )
    if ctx.user_profile:
        system += f"\nKnown caller profile: {json.dumps(ctx.user_profile, ensure_ascii=True)}"
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": transcript},
    ]


async def _classify_transcript_with_model(ctx: CallContext, transcript: str) -> tuple[str, float, str]:
    if not transcript.strip():
        return "unknown", 0.0, "neutral"

    llm_provider = (ctx.providers.llm_provider or settings.default_llm_provider or "openai").lower()
    messages = _intent_classifier_prompt(ctx, transcript)

    try:
        if llm_provider == "ollama":
            base_url = (ctx.providers.llm_options.get("base_url") or settings.ollama_base_url).rstrip("/")
            url = f"{base_url}/chat/completions"
            payload = {
                "model": ctx.providers.llm_model or settings.ollama_llm_model,
                "messages": messages,
                "temperature": 0.1,
            }
            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=12)) as response:
                    response.raise_for_status()
                    data = await response.json()
            raw = (((data.get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip()
            return _extract_intent_json(raw)

        if llm_provider in {"google", "gemini_live"}:
            api_key = ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.google_api_key
            if api_key:
                model = settings.google_llm_model if llm_provider == "gemini_live" else (ctx.providers.llm_model or settings.google_llm_model)
                url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
                payload = {
                    "contents": [{"role": "user", "parts": [{"text": f"{messages[0]['content']}\n\nTranscript: {transcript}"}]}],
                    "generationConfig": {"temperature": 0.1},
                }
                async with aiohttp.ClientSession() as session:
                    async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=12)) as response:
                        response.raise_for_status()
                        data = await response.json()
                parts = (((data.get("candidates") or [{}])[0].get("content") or {}).get("parts") or [])
                raw = "".join(part.get("text", "") for part in parts).strip()
                return _extract_intent_json(raw)

        api_key = ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.openai_api_key
        if api_key:
            url = "https://api.openai.com/v1/chat/completions"
            payload = {
                "model": ctx.providers.llm_model or settings.openai_llm_model,
                "messages": messages,
                "temperature": 0.1,
            }
            headers = {"Authorization": f"Bearer {api_key}"}
            async with aiohttp.ClientSession(headers=headers) as session:
                async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=12)) as response:
                    response.raise_for_status()
                    data = await response.json()
            raw = (((data.get("choices") or [{}])[0].get("message") or {}).get("content") or "").strip()
            return _extract_intent_json(raw)
    except Exception as exc:
        logger.warning("Transcript intent classification failed: call=%s provider=%s err=%s", ctx.call_id, llm_provider, exc)

    fallback_intent = _infer_intent_from_user_text(transcript, "unknown")
    fallback_conf = 0.55 if fallback_intent != "unknown" else 0.0
    return fallback_intent, fallback_conf, "neutral"


def _fast_transcript_intent(text: str) -> tuple[str, float, str]:
    normalized = _normalize_text_for_compare(text)
    if not normalized:
        return "unknown", 0.0, "neutral"
    if _is_simple_greeting(text):
        return "greeting", 0.98, "neutral"
    if any(token in normalized for token in ["bye", "goodbye", "thank you", "thanks", "phir milte", "alvida"]):
        return "goodbye", 0.96, "positive"
    if any(token in normalized for token in ["human", "agent", "representative", "insaan", "aadmi se baat", "person se baat"]):
        return "human_agent", 0.95, "neutral"
    if any(token in normalized for token in ["demo", "walkthrough", "dikhaiye", "dikhao"]):
        return "demo_request", 0.92, "positive"
    if any(token in normalized for token in ["callback", "call back", "baad mein call", "later call"]):
        return "callback_request", 0.92, "neutral"
    if any(token in normalized for token in ["price", "pricing", "cost", "rate", "plan", "quote", "quotation", "keemat", "daam"]):
        return "pricing", 0.94, "neutral"
    if any(token in normalized for token in ["support", "issue", "problem", "error", "not working", "technical", "trouble", "dikat", "samasya"]):
        return "technical_support", 0.93, "negative"
    if any(token in normalized for token in ["compare", "comparison", "difference", "vs", "better"]):
        return "product_comparison", 0.9, "neutral"
    if any(token in normalized for token in ["feature", "features", "capability", "kya kya", "kaisa product", "product"]):
        return "feature_question", 0.88, "neutral"
    if any(token in normalized for token in ["not interested", "no need", "mat chahiye", "nahi chahiye", "busy"]):
        return "not_interested", 0.9, "negative"
    if any(token in normalized for token in ["need", "want", "looking for", "requirement", "chahiye", "chaahiye", "mujhe", "hume", "phone system", "telephone", "sip", "trunk"]):
        return "interested", 0.82, "neutral"
    return "unknown", 0.0, "neutral"


def _normalize_text_for_compare(text: str) -> str:
    text = text.lower().strip()
    text = re.sub(r"[^\w\s]", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def _extract_user_profile(text: str) -> dict:
    profile: dict = {}
    if not text:
        return profile

    name_match = re.search(r"\b(?:i am|i'm|my name is|this is)\s+([A-Za-z][A-Za-z\s]{1,40})", text, flags=re.IGNORECASE)
    if name_match:
        profile["name"] = name_match.group(1).strip(" .,!?")

    hindi_name_match = re.search(r"(?:mera naam|meri name|naam hai)\s+([A-Za-z\u0900-\u097F][A-Za-z\u0900-\u097F\s]{1,40})", text, flags=re.IGNORECASE)
    if hindi_name_match and "name" not in profile:
        profile["name"] = hindi_name_match.group(1).strip(" .,!?")

    devanagari_name_match = re.search(r"(?:मेरा नाम|ये मेरा नाम|यह मेरा नाम|नाम है)\s+([\u0900-\u097F\s]{2,40})", text)
    if devanagari_name_match and "name" not in profile:
        candidate = devanagari_name_match.group(1).strip(" .,!?")
        candidate = re.sub(r"\s+है$", "", candidate).strip()
        if candidate:
            profile["name"] = candidate

    company_match = re.search(r"\b(?:from|at|with)\s+([A-Za-z][A-Za-z0-9&.,\-\s]{1,50})", text, flags=re.IGNORECASE)
    if company_match:
        profile["company"] = company_match.group(1).strip(" .,!?")

    explicit_company_match = re.search(r"(?:my company is|meri company hai|company hai|company is)\s+([A-Za-z0-9&.,\-\s]+)", text, flags=re.IGNORECASE)
    if explicit_company_match:
        profile["company"] = explicit_company_match.group(1).strip(" .,!?")

    devanagari_company_match = re.search(r"(?:मेरी कंपनी है|मेरी company है|company है)\s+([A-Za-z0-9\u0900-\u097F&.,\-\s]+)", text, flags=re.IGNORECASE)
    if devanagari_company_match:
        profile["company"] = devanagari_company_match.group(1).strip(" .,!?")

    domain_match = re.search(r"\b([A-Za-z0-9\-]+\s*(?:dot|\.)\s*(?:com|in|org|net))\b", text, flags=re.IGNORECASE)
    if domain_match:
        profile["company"] = domain_match.group(1).replace(" dot ", ".").replace(" ", "")

    requirement_match = re.search(r"\b(?:need|looking for|want|require|searching for)\b(.+)", text, flags=re.IGNORECASE)
    if requirement_match:
        profile["requirement"] = requirement_match.group(1).strip(" .,!?")

    hindi_requirement_match = re.search(
        r"(?:मुझे|हमें|अभी बताइए|जानना है|चाहिए|कीमत|प्राइस|प्रोडक्ट|product|pricing|price|sip)\s+(.+)",
        text,
        flags=re.IGNORECASE,
    )
    if hindi_requirement_match and "requirement" not in profile:
        candidate = hindi_requirement_match.group(0).strip(" .,!?")
        if candidate:
            profile["requirement"] = candidate

    if "requirement" not in profile and re.search(r"\b(phone system|telephone|ivr|calling|dialer|support|pricing|product|price|sip)\b", text, flags=re.IGNORECASE):
        profile["requirement"] = text.strip(" .,!?")

    return profile


def _conversation_state_prompt(ctx: CallContext) -> str:
    missing: list[str] = []
    if not (ctx.user_profile.get("name") or (ctx.contact or {}).get("name")):
        missing.append("name")
    if not (ctx.user_profile.get("company") or (ctx.contact or {}).get("company")):
        missing.append("company")
    if not ctx.user_profile.get("requirement"):
        missing.append("requirement")

    lines = [
        "\n\nCONVERSATION STATE:",
        f"- Assistant turns already spoken: {ctx.turn_count}",
        f"- User profile collected so far: {json.dumps(ctx.user_profile, ensure_ascii=True)}",
    ]
    if missing:
        lines.append(f"- Collect these details early in the call: {', '.join(missing)}")
    else:
        lines.append("- Basic user details are collected. Continue with personalized follow-up.")
    if ctx.conversation_history:
        lines.append("- Recent conversation:")
        for turn in ctx.conversation_history[-6:]:
            content = (turn.get("content") or "").strip()
            if content:
                lines.append(f"  {turn.get('role', 'unknown')}: {content[:180]}")
    return "\n".join(lines)


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


def _looks_like_meta_reply(text: str) -> bool:
    lowered = text.lower()
    bad_markers = [
        "*note:",
        "you have to mention",
        "before i can help",
        "before i can even think",
        "i'm here to help, so",
    ]
    return any(marker in lowered for marker in bad_markers)


def _is_simple_greeting(text: str) -> bool:
    normalized = _normalize_text_for_compare(text)
    return normalized in {
        "hello",
        "hi",
        "hey",
        "hello ji",
        "hi ji",
        "good morning",
        "good afternoon",
        "good evening",
        "namaste",
        "namaskar",
    }


def _is_low_information_fragment(text: str) -> bool:
    normalized = _normalize_text_for_compare(text)
    return normalized in {
        "",
        "ji",
        "जी",
        "haan",
        "han",
        "ha",
        "hai",
        "है",
        "h",
        "ok",
        "okay",
        "accha",
        "achha",
        "अच्छा",
        "are",
        "arre",
        "अरे",
        "dot",
        "com",
        "party hai",
    }


def _active_listening_reply(ctx: CallContext) -> str:
    return f"Ji, {_listening_phrase(ctx)}. Sabse pehle aapka naam bata dijiye."


def _missing_profile_fields(ctx: CallContext) -> list[str]:
    missing: list[str] = []
    if not (ctx.user_profile.get("name") or (ctx.contact or {}).get("name")):
        missing.append("name")
    if not (ctx.user_profile.get("company") or (ctx.contact or {}).get("company")):
        missing.append("company")
    if not ctx.user_profile.get("requirement"):
        missing.append("requirement")
    return missing


def _next_onboarding_reply(ctx: CallContext, user_text: str) -> str | None:
    missing = _missing_profile_fields(ctx)
    if not missing:
        return None
    if missing[0] == "name" and not (ctx.user_profile.get("name") or (ctx.contact or {}).get("name")):
        ctx.user_profile["_name_retry"] = int(ctx.user_profile.get("_name_retry", 0)) + 1
    if missing[0] == "company" and not (ctx.user_profile.get("company") or (ctx.contact or {}).get("company")):
        ctx.user_profile["_company_retry"] = int(ctx.user_profile.get("_company_retry", 0)) + 1
    if missing[0] == "requirement" and not ctx.user_profile.get("requirement"):
        ctx.user_profile["_requirement_retry"] = int(ctx.user_profile.get("_requirement_retry", 0)) + 1
    if _is_simple_greeting(user_text):
        first_missing = missing[0]
        if first_missing == "name":
            return _active_listening_reply(ctx)
        if first_missing == "company":
            return f"Ji, {_listening_phrase(ctx)}. Ab aapki company ka naam bata dijiye."
        return f"Ji, {_listening_phrase(ctx)}. Ab batayein aapko phone system mein exactly kis cheez ki requirement hai."
    first_missing = missing[0]
    if first_missing == "name":
        if int(ctx.user_profile.get("_name_retry", 0)) >= 3:
            return "Kripya apna naam dheere se boliye, ya English letters mein spell kijiye."
        return "Sure. Sabse pehle aapka naam bata dijiye."
    if first_missing == "company":
        if _normalize_text_for_compare(user_text) in {"meri company hai", "my company is", "company hai"}:
            return "Ji, company ka naam poora bol dijiye."
        if int(ctx.user_profile.get("_company_retry", 0)) >= 3:
            return "Kripya company ka naam poora boliye. Agar website hai to domain bhi bol sakte hain."
        return "Thank you. Ab aapki company ka naam bata dijiye."
    requirement = ctx.user_profile.get("requirement")
    if requirement:
        return "Samajh gaya. Aapko phone system ke baare mein help chahiye. Ab ek line mein batayein ki aapki exact requirement kya hai."
    if int(ctx.user_profile.get("_requirement_retry", 0)) >= 3:
        return "Ek simple line mein batayein: aapko pricing, features, support, ya SIP setup mein se kis cheez mein help chahiye?"
    return "Theek hai. Ab batayein aapko phone system mein exactly kis cheez ki requirement hai."


def _is_onboarding_complete(ctx: CallContext) -> bool:
    return not _missing_profile_fields(ctx)


def _should_run_onboarding(ctx: CallContext) -> bool:
    return ctx.is_bot_mode and not _is_onboarding_complete(ctx)


def _infer_intent_from_user_text(user_text: str, fallback: str) -> str:
    normalized = _normalize_text_for_compare(user_text)
    if _is_simple_greeting(user_text):
        return "greeting"
    if any(token in normalized for token in ["price", "pricing", "cost", "plan", "rate"]):
        return "pricing"
    if any(token in normalized for token in ["support", "issue", "problem", "help"]):
        return "technical_support"
    if any(token in normalized for token in ["interested", "need", "want", "looking for", "system", "telephone"]):
        return "interested"
    return fallback


def _asks_for_collected_profile(text: str) -> bool:
    normalized = _normalize_text_for_compare(text)
    markers = [
        "your name",
        "aapka naam",
        "company name",
        "company ka naam",
        "company ka naam hai",
        "what is your company",
        "tell me your company",
        "could you tell me your name",
        "kitne people",
        "kitne log",
        "staff hain",
    ]
    return any(marker in normalized for marker in markers)


def _post_onboarding_guard_reply(ctx: CallContext, user_text: str, response_text: str) -> str | None:
    if not _is_onboarding_complete(ctx):
        return None
    normalized_user = _normalize_text_for_compare(user_text)
    if _is_simple_greeting(response_text) or _asks_for_collected_profile(response_text):
        requirement = ctx.user_profile.get("requirement") or user_text
        company = ctx.user_profile.get("company")
        if company:
            return (
                f"Samajh gaya. {company} ke liye aapko {requirement} mein help chahiye. "
                "Kya aap pricing, features, ya setup details chahte hain?"
            )
        return (
            f"Samajh gaya. Aapko {requirement} mein help chahiye. "
            "Kya aap pricing, features, ya setup details chahte hain?"
        )
    if ctx.user_profile.get("company") and any(token in normalized_user for token in ["sip", "pricing", "price", "solution", "trunk", "planning", "features", "setup"]):
        normalized_response = _normalize_text_for_compare(response_text)
        if any(marker in normalized_response for marker in ["company ka naam", "your company", "what company", "staff hain", "kitne people"]):
            return (
                f"Samajh gaya. {ctx.user_profile.get('company')} ke liye aapko {ctx.user_profile.get('requirement') or user_text} chahiye. "
                f"{_help_phrase(ctx).capitalize()}. Kya aap pricing, features, ya setup process se shuru karna chahte hain?"
            )
    return None


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


class TranscriptIntentProcessor(FrameProcessor):
    def __init__(self, ctx: CallContext, tracker: IntentTracker, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.tracker = tracker
        self._last_text = ""
        self._last_intent = ""
        self._last_at = 0.0
        self._inflight_texts: set[str] = set()

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, TranscriptionFrame):
            text = (frame.text or "").strip()
            if text and not _is_low_information_fragment(text):
                asyncio.create_task(self._classify(text))
        await self.push_frame(frame, direction)

    async def _classify(self, text: str):
        normalized = _normalize_text_for_compare(text)
        if not normalized:
            return
        now = time.time()
        if normalized == self._last_text and now - self._last_at < 4:
            return
        if normalized in self._inflight_texts:
            return
        fast_intent, fast_confidence, fast_sentiment = _fast_transcript_intent(text)
        if fast_intent != "unknown":
            self._publish_intent(text, fast_intent, fast_confidence, fast_sentiment, "transcript_fast")
            if fast_confidence >= 0.9:
                return
        self._inflight_texts.add(normalized)
        try:
            intent, confidence, sentiment_hint = await _classify_transcript_with_model(self.ctx, text)
            if intent == "unknown" and confidence <= 0:
                return
            if fast_intent != "unknown" and intent == fast_intent and confidence <= fast_confidence:
                return
            self._publish_intent(text, intent, confidence, sentiment_hint, "transcript_model")
        finally:
            self._inflight_texts.discard(normalized)

    def _publish_intent(self, text: str, intent: str, confidence: float, sentiment_hint: str, source: str):
        normalized = _normalize_text_for_compare(text)
        now = time.time()
        if intent == self._last_intent and normalized == self._last_text and now - self._last_at < 10:
            return
        self._last_text = normalized
        self._last_intent = intent
        self._last_at = now
        logger.info(
            "Transcript intent classified: call=%s source=%s intent=%s conf=%.3f text=%s",
            self.ctx.call_id,
            source,
            intent,
            confidence,
            text[:200],
        )
        self.tracker.record(text, intent, confidence, speaker="CALLER", sentiment_hint=sentiment_hint)
        self.ctx.add_intent(text, intent, confidence, "CALLER_TRANSCRIPT")
        asyncio.create_task(
            persist_analytics_event(
                call_id=self.ctx.call_id,
                tenant_id=self.ctx.tenant_id,
                product_code=self.ctx.product_code,
                speaker="CALLER",
                event_type="transcript_intent",
                utterance=text,
                intent=intent,
                intent_confidence=confidence,
                tone_label=sentiment_hint,
                turn_index=self.ctx.turn_count,
                metadata_json={"source": source},
            )
        )
        _store_event(
            self.ctx.call_id,
            {
                "type": "caller_intent",
                "text": text,
                "intent": intent,
                "intent_confidence": confidence,
                "sentiment_hint": sentiment_hint,
                "source": source,
            },
        )


class UserLLMTriggerProcessor(FrameProcessor):
    """Convert final STT transcriptions into direct LLM context frames."""

    def __init__(self, ctx: CallContext, context: OpenAILLMContext, **kw):
        super().__init__(**kw)
        self.ctx = ctx
        self.context = context
        self._pending_text = ""
        self._pending_task: asyncio.Task | None = None

    async def _emit_assistant_reply(self, text: str, intent: str = "onboarding", confidence: float = 1.0):
        self.ctx.add_conversation_turn("assistant", text)
        self.context.add_message({"role": "assistant", "content": text})
        while len(self.context.messages) > 12:
            self.context.messages.pop(1)
        asyncio.create_task(
            persist_analytics_event(
                call_id=self.ctx.call_id,
                tenant_id=self.ctx.tenant_id,
                product_code=self.ctx.product_code,
                speaker="BOT",
                event_type="bot_response",
                utterance=text,
                intent=intent,
                intent_confidence=confidence,
                turn_index=self.ctx.turn_count,
                metadata_json={"source": "onboarding_controller"},
            )
        )
        _store_event(
            self.ctx.call_id,
            {"type": "bot_response", "text": text, "intent": intent, "intent_confidence": confidence},
        )
        await self.push_frame(TTSSpeakFrame(text=text, append_to_context=False))

    def _looks_incomplete(self, text: str) -> bool:
        normalized = text.strip().lower()
        if not normalized:
            return True
        if _is_low_information_fragment(text):
            return True
        missing = _missing_profile_fields(self.ctx)
        current_field = missing[0] if missing else None
        if len(normalized.split()) <= 2:
            if current_field in {"company", "requirement"}:
                return True
        continuation_phrases = (
            "i want",
            "we want",
            "looking for",
            "need",
            "phone system",
            "telephone",
            "pricing",
        )
        if any(normalized.endswith(phrase) for phrase in continuation_phrases):
            return True
        if current_field == "company":
            if any(prefix in normalized for prefix in ("meri company", "company ka naam", "company hai", "my company")):
                return True
            if re.fullmatch(r"[a-z0-9\-]+", normalized):
                return True
            if normalized.endswith(("dot", "dot com", "com", "dot in", "in", "dot org", "org")):
                return True
        if current_field == "requirement":
            if normalized.endswith(("pricing", "price", "sip", "product", "feature", "support")):
                return True
        if normalized.endswith((",", "-", "/", "&")):
            return True
        return False

    async def _emit_pending(self, direction: FrameDirection):
        text = self._pending_text.strip()
        self._pending_text = ""
        self._pending_task = None
        if not text:
            return
        if _is_low_information_fragment(text):
            return
        self.ctx.user_profile.update(_extract_user_profile(text))
        if self.ctx.user_profile.get("name"):
            self.ctx.user_profile.pop("_name_retry", None)
        if self.ctx.user_profile.get("company"):
            self.ctx.user_profile.pop("_company_retry", None)
        if self.ctx.user_profile.get("requirement"):
            self.ctx.user_profile.pop("_requirement_retry", None)
        self.ctx.add_conversation_turn("user", text)
        if self.context.messages:
            first_message = self.context.messages[0]
            if isinstance(first_message, dict) and first_message.get("role") == "system":
                first_message["content"] = build_system_prompt(self.ctx)
        if _should_run_onboarding(self.ctx):
            reply = _next_onboarding_reply(self.ctx, text)
            if reply:
                await self._emit_assistant_reply(reply)
                return
        self.context.add_message({"role": "user", "content": text})
        while len(self.context.messages) > 12:
            self.context.messages.pop(1)
        logger.info("Triggering LLM from transcript: call=%s text=%s", self.ctx.call_id, text[:200])
        await self.push_frame(OpenAILLMContextFrame(self.context), direction)

    async def _flush_pending(self, direction: FrameDirection):
        missing = _missing_profile_fields(self.ctx)
        current_field = missing[0] if missing else None
        delay = 0.4
        if current_field == "company":
            delay = 1.0
        elif current_field == "requirement":
            delay = 0.7
        await asyncio.sleep(delay)
        await self._emit_pending(direction)

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)

        if isinstance(frame, TranscriptionFrame):
            text = frame.text.strip()
            if not text:
                return
            if self._pending_task and not self._pending_task.done():
                self._pending_task.cancel()
                combined = f"{self._pending_text} {text}".strip()
            else:
                combined = text
            self._pending_text = combined
            if self._looks_incomplete(combined):
                self._pending_task = asyncio.create_task(self._flush_pending(direction))
                return
            await self._emit_pending(direction)
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
            extracted = _extract_response_from_json_like_text(raw)
            if extracted:
                resp = extracted
        resp = _strip_emoji(resp).strip()
        utt = self.ctx.conversation_history[-1]["content"] if self.ctx.conversation_history else ""
        intent = _infer_intent_from_user_text(utt, intent)
        if self.ctx.turn_count > 0 and (intent == "greeting" or _is_simple_greeting(utt) or _is_simple_greeting(resp)):
            resp = _active_listening_reply(self.ctx)
        onboarding_reply = _next_onboarding_reply(self.ctx, utt)
        if onboarding_reply:
            resp = onboarding_reply
            conf = max(conf, 0.85 if intent != "unknown" else 0.7)
        if _is_near_duplicate_reply(resp, self.ctx.conversation_history):
            logger.warning(
                "Suppressing near-duplicate assistant reply: call=%s response=%s",
                self.ctx.call_id,
                resp[:200],
            )
            resp = self.ctx.bot.fallback_message or "Ji, aap apni exact requirement batayein."
        if _looks_like_meta_reply(resp):
            logger.warning("Suppressing meta/policy-style assistant reply: call=%s response=%s", self.ctx.call_id, resp[:200])
            resp = "I understand you need a phone system. Please tell me your name and company, and then I can suggest the right option."
        guarded = _post_onboarding_guard_reply(self.ctx, utt, resp)
        if guarded:
            logger.warning("Rewriting post-onboarding regression reply: call=%s response=%s", self.ctx.call_id, resp[:200])
            resp = guarded
        logger.info("LLM parsed: call=%s intent=%s conf=%.3f escalate=%s response=%s",
                    self.ctx.call_id, intent, conf, esc, resp[:200])

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
- Keep one language dominant per reply. If the caller speaks mostly English, reply mostly in English with only light natural Hindi. If the caller speaks mostly Hindi, reply mostly in Hindi with light natural English.
- Never force unnatural Hinglish. Avoid phrases that sound translated, theatrical, or grammatically broken.
- After the first bot greeting, do not greet again.
- If the caller only says hello/hi after your greeting, briefly acknowledge and ask one business question.
- In the first 1-2 user turns, collect missing basics in a natural order: name, company, and requirement, unless already known.
- Show that you heard the caller by briefly referring to the relevant part of what they said before asking the next question.
- Do not ignore the caller's latest message. Respond to it first, then move the conversation forward.
- Avoid repeating filler words like "haan ji", "sure", "okay", or the same acknowledgement in back-to-back turns.
- If you already asked for a detail and the caller answered, do not ask for the same detail again.
- Never output internal notes, policy reminders, instructions, stage directions, or commentary about what the user must do.
- Do not scold the caller or say things like "before I can help" or "you have to mention".
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

    prompt += _conversation_state_prompt(ctx)

    # Intent suffix (forces JSON response with intent detection)
    prompt += INTENT_SUFFIX

    if ctx.bot.allowed_intents:
        prompt += f"\nPreferred intents: {', '.join(ctx.bot.allowed_intents)}"

    return prompt


# ═══════════════════════════════════════════════════════════
# PIPELINE BUILDER
# ═══════════════════════════════════════════════════════════

def build_pipeline(ctx: CallContext, websocket) -> tuple[PipelineTask, PipelineRunner]:
    _validate_provider_inputs(ctx)
    openai_key = ctx.providers.llm_api_key or settings.openai_api_key
    deepgram_key = ctx.providers.stt_api_key or settings.deepgram_api_key
    aiohttp_session = None

    # VAD
    logger.info("Initializing VAD: call=%s sample_rate=%s", ctx.call_id, 16000)
    vad = SileroVADAnalyzer(sample_rate=16000, params=VADParams(stop_secs=0.8))

    # Transport
    tts_provider_name = (ctx.providers.tts_provider or settings.default_tts_provider).lower()
    tts_sample_rate = _resolve_tts_sample_rate(ctx, tts_provider_name)
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

    stt_provider_name = (ctx.providers.stt_provider or settings.default_stt_provider or "deepgram").lower()
    stt_language = _resolve_stt_language(ctx)
    logger.info("Initializing STT: call=%s provider=%s model=%s language=%s",
                ctx.call_id, ctx.providers.stt_provider, ctx.providers.stt_model or settings.deepgram_stt_model,
                stt_language)
    try:
        if stt_provider_name == "google":
            _, _, _, _, GoogleSTTService, _ = _load_google_services()
            google_stt_model = ctx.providers.stt_model or ctx.providers.stt_options.get("model") or "latest_long"
            google_stt_location = ctx.providers.stt_options.get("location") or settings.google_location or "global"
            logger.info(
                "Using Google STT: call=%s model=%s language=%s location=%s",
                ctx.call_id,
                google_stt_model,
                stt_language,
                google_stt_location,
            )
            stt = GoogleSTTService(
                sample_rate=16000,
                params=GoogleSTTService.InputParams(
                    languages=[_resolve_google_stt_language(ctx)],
                    model=google_stt_model,
                    enable_automatic_punctuation=True,
                    enable_interim_results=True,
                    enable_voice_activity_events=True,
                ),
                **_google_stt_credentials_kwargs(ctx.providers.stt_options),
            )
        elif stt_provider_name == "openai":
            openai_stt_model = ctx.providers.stt_model or ctx.providers.stt_options.get("model") or settings.openai_stt_model
            openai_stt_base_url = ctx.providers.stt_options.get("base_url")
            logger.info(
                "Using OpenAI Realtime STT: call=%s model=%s language=%s base_url=%s",
                ctx.call_id,
                openai_stt_model,
                stt_language,
                openai_stt_base_url or "default",
            )
            stt = OpenAIRealtimeSTTService(
                api_key=ctx.providers.stt_api_key or ctx.providers.stt_options.get("api_key") or openai_key,
                model=openai_stt_model,
                base_url=openai_stt_base_url or "wss://api.openai.com/v1/realtime",
                language=_resolve_openai_stt_language(ctx),
                turn_detection=False,
                should_interrupt=False,
            )
        else:
            stt = DeepgramSTTService(
                api_key=ctx.providers.stt_api_key or ctx.providers.stt_options.get("api_key") or deepgram_key,
                live_options=LiveOptions(
                    model=ctx.providers.stt_model or settings.deepgram_stt_model,
                    language=stt_language,
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
    except Exception as exc:
        logger.exception(
            "Provider initialization failed: call=%s provider_type=stt provider=%s tenant=%s bot_id=%s",
            ctx.call_id,
            stt_provider_name,
            ctx.tenant_id,
            ctx.bot.bot_id,
        )
        raise ProviderSetupError("stt", stt_provider_name, _provider_error_reason(exc)) from exc

    # LLM
    logger.info("Initializing LLM: call=%s provider=%s model=%s", ctx.call_id, ctx.providers.llm_provider,
                ctx.providers.llm_model or settings.openai_llm_model)
    llm_provider = (ctx.providers.llm_provider or settings.default_llm_provider or "openai").lower()
    try:
        if llm_provider == "ollama":
            llm_model = ctx.providers.llm_model or settings.ollama_llm_model
            ollama_base_url = ctx.providers.llm_options.get("base_url", settings.ollama_base_url)
            logger.info("Using Ollama LLM: call=%s model=%s base_url=%s", ctx.call_id, llm_model, ollama_base_url)
            llm = OLLamaLLMService(model=llm_model, base_url=ollama_base_url)
        elif llm_provider == "google":
            GoogleLLMService, _, _, _, _, _ = _load_google_services()
            llm_model = ctx.providers.llm_model or settings.google_llm_model
            logger.info("Using Google LLM: call=%s model=%s", ctx.call_id, llm_model)
            llm = GoogleLLMService(
                api_key=ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.google_api_key,
                model=llm_model,
            )
        elif llm_provider == "gemini_live":
            _, _, _, _, _, GeminiLiveLLMService = _load_google_services()
            llm_model = ctx.providers.llm_model or settings.google_gemini_live_llm_model
            llm_voice = _resolve_native_audio_voice(ctx)
            logger.info("Using Gemini Live LLM: call=%s model=%s voice=%s language=%s", ctx.call_id, llm_model, llm_voice, ctx.bot.language)
            llm = GeminiLiveLLMService(
                api_key=ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or settings.google_api_key,
                model=llm_model,
                voice_id=llm_voice,
                system_instruction=build_system_prompt(ctx),
            )
        elif llm_provider == "vertex":
            _, _, GoogleVertexLLMService, _, _, _ = _load_google_services()
            llm_model = ctx.providers.llm_model or settings.vertex_llm_model
            logger.info(
                "Using Vertex LLM: call=%s model=%s project=%s location=%s",
                ctx.call_id,
                llm_model,
                ctx.providers.llm_options.get("project_id", settings.google_project_id),
                ctx.providers.llm_options.get("location", settings.google_location),
            )
            llm = GoogleVertexLLMService(
                model=llm_model,
                **_google_credentials_kwargs(ctx.providers.llm_options),
            )
        else:
            llm_model = ctx.providers.llm_model or settings.openai_llm_model
            llm = OpenAILLMService(
                api_key=ctx.providers.llm_api_key or ctx.providers.llm_options.get("api_key") or openai_key,
                model=llm_model,
            )
    except Exception as exc:
        logger.exception(
            "Provider initialization failed: call=%s provider_type=llm provider=%s tenant=%s bot_id=%s",
            ctx.call_id,
            llm_provider,
            ctx.tenant_id,
            ctx.bot.bot_id,
        )
        raise ProviderSetupError("llm", llm_provider, _provider_error_reason(exc)) from exc

    # TTS
    try:
        if _is_native_audio_llm_provider(llm_provider):
            active_tts_provider = "gemini_live"
            tts = None
        elif tts_provider_name == "google":
            _, GoogleTTSService, _, GeminiTTSService, _, _ = _load_google_services()
            google_kwargs = _google_tts_credentials_kwargs(ctx.providers.tts_options)
            google_voice = _resolve_tts_voice(ctx, "google")
            google_mode = str(ctx.providers.tts_options.get("mode", settings.google_tts_mode)).lower()
            logger.info(
                "Initializing TTS: call=%s provider=google mode=%s voice=%s gender=%s language=%s endpoint=%s",
                ctx.call_id,
                google_mode,
                google_voice,
                ctx.bot.voice_gender or ctx.providers.tts_gender,
                ctx.bot.language,
                "default",
            )
            if google_mode == "gemini":
                tts = GeminiTTSService(
                    model=ctx.providers.tts_options.get("model", settings.google_gemini_tts_model),
                    voice_id=google_voice,
                    sample_rate=tts_sample_rate,
                    params=GeminiTTSService.InputParams(language=ctx.bot.language),
                    **google_kwargs,
                )
            else:
                tts = GoogleTTSService(
                    voice_id=google_voice,
                    sample_rate=tts_sample_rate,
                    params=GoogleTTSService.InputParams(language=ctx.bot.language),
                    **google_kwargs,
                )
            active_tts_provider = "google"
        elif ctx.providers.tts_provider == "deepgram" and deepgram_key:
            logger.info("Initializing TTS: call=%s provider=deepgram voice=%s", ctx.call_id,
                        ctx.providers.tts_voice or settings.deepgram_tts_model)
            tts = DeepgramTTSService(
                api_key=ctx.providers.tts_api_key or ctx.providers.tts_options.get("api_key") or deepgram_key,
                voice=ctx.providers.tts_voice or settings.deepgram_tts_model,
            )
            active_tts_provider = "deepgram"
        else:
            logger.info("Initializing TTS: call=%s provider=openai model=%s voice=%s", ctx.call_id,
                        ctx.providers.tts_model or settings.openai_tts_model,
                        ctx.providers.tts_voice or settings.openai_tts_voice)
            tts = OpenAITTSService(
                api_key=ctx.providers.tts_api_key or ctx.providers.tts_options.get("api_key") or openai_key,
                model=ctx.providers.tts_model or settings.openai_tts_model,
                voice=ctx.providers.tts_voice or settings.openai_tts_voice,
            )
            tts._settings.language = ctx.bot.language or None
            active_tts_provider = "openai"
    except Exception as exc:
        logger.exception(
            "Provider initialization failed: call=%s provider_type=tts provider=%s tenant=%s bot_id=%s",
            ctx.call_id,
            tts_provider_name,
            ctx.tenant_id,
            ctx.bot.bot_id,
        )
        raise ProviderSetupError("tts", tts_provider_name, _provider_error_reason(exc)) from exc

    # LLM context
    system_prompt = build_system_prompt(ctx)
    logger.debug("System prompt built: call=%s length=%s", ctx.call_id, len(system_prompt))
    messages = [{"role": "system", "content": system_prompt}]
    if _is_native_audio_llm_provider(llm_provider):
        context = LLMContext(messages=messages)
        context_aggregator = LLMContextAggregatorPair(context)
    else:
        context = OpenAILLMContext(messages=messages)
        context_aggregator = llm.create_context_aggregator(context)

    # Custom processors
    esc_intents, esc_actions = _parse_escalation(ctx)
    tracker = IntentTracker(escalation_intents=esc_intents, intent_actions=esc_actions)
    sentiment = SentimentAnalyzer() if ctx.sentiment_enabled else None
    transcript_relay = TranscriptRelayProcessor(ctx, sentiment)
    transcript_intent = TranscriptIntentProcessor(ctx, tracker)
    user_trigger = UserLLMTriggerProcessor(ctx, context) if not _is_native_audio_llm_provider(llm_provider) else None
    intent_check = IntentCheckProcessor(ctx, tracker)
    call_end = CallEndProcessor(ctx, tracker)

    # Build processor chain
    if _is_native_audio_llm_provider(llm_provider):
        processors = [
            transport.input(),
            input_noise_reducer,
            transcript_relay,
            transcript_intent,
            llm,
            intent_check,
        ]
    else:
        processors = [
            transport.input(),
            input_noise_reducer,
            stt,
            transcript_relay,
            transcript_intent,
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

    # Keep provider audio untouched unless RVC is active.
    post_process_enabled = ctx.providers.rvc_enabled
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
