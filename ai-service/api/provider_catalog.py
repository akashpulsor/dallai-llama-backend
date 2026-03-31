from copy import deepcopy

from fastapi import APIRouter, HTTPException, Query

router = APIRouter(prefix="/providers", tags=["providers"])


def _language(code: str, label: str) -> dict:
    return {"code": code, "label": label}


def _voice(voice_id: str, label: str, gender: str, language: str) -> dict:
    return {
        "id": voice_id,
        "label": label,
        "gender": gender,
        "language": language,
    }


PROVIDER_CATALOG = {
    "storage_schema": {
        "provider_master_fields": [
            "provider",
            "modality",
            "display_name",
            "enabled",
            "default_model",
            "default_voice_id",
            "default_gender",
            "default_language",
            "supported_languages_json",
            "supported_voices_json",
            "credential_fields_json",
            "base_url",
            "project_id",
            "location",
            "api_key",
            "credentials_json",
            "credentials_path",
            "metadata_json",
        ],
        "tenant_provider_fields": [
            "tenant_id",
            "provider",
            "modality",
            "api_key",
            "base_url",
            "project_id",
            "location",
            "credentials_json",
            "credentials_path",
            "metadata_json",
        ],
        "bot_config_fields": [
            "llm_provider",
            "llm_model",
            "stt_provider",
            "stt_model",
            "tts_provider",
            "tts_model",
            "tts_voice",
            "tts_gender",
            "language",
            "bot.voice_provider",
            "bot.voice_id",
            "bot.voice_gender",
            "bot.voice_speed",
            "provider_configs",
            "provider_credentials",
            "tenant_provider_credentials",
        ],
    },
    "llm": [
        {
            "provider": "openai",
            "label": "OpenAI",
            "credential_fields": ["api_key"],
            "default_model": "gpt-4o-mini",
            "languages": [_language("multi", "Multilingual")],
        },
        {
            "provider": "ollama",
            "label": "Ollama",
            "credential_fields": ["base_url"],
            "default_model": "gemma2:2b",
            "languages": [_language("multi", "Model dependent")],
        },
        {
            "provider": "google",
            "label": "Google Gemini API",
            "credential_fields": ["api_key"],
            "default_model": "gemini-2.5-flash-lite",
            "languages": [_language("multi", "Multilingual")],
        },
        {
            "provider": "gemini_live",
            "label": "Gemini Live",
            "credential_fields": ["api_key"],
            "default_model": "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "languages": [
                _language("hi-IN", "Hindi (India)"),
                _language("en-IN", "English (India)"),
                _language("en-US", "English (US)"),
            ],
            "native_audio": True,
            "supports_image_input": True,
            "supports_test_console": True,
        },
        {
            "provider": "vertex",
            "label": "Google Vertex AI",
            "credential_fields": ["project_id", "location", "credentials_path", "credentials_json"],
            "default_model": "gemma3-4b-it",
            "languages": [_language("multi", "Multilingual")],
        },
    ],
    "stt": [
        {
            "provider": "deepgram",
            "label": "Deepgram",
            "credential_fields": ["api_key"],
            "default_model": "nova-2",
            "languages": [
                _language("en", "English"),
                _language("en-US", "English (US)"),
                _language("hi", "Hindi"),
                _language("hi-IN", "Hindi (India)"),
            ],
        },
        {
            "provider": "google",
            "label": "Google Speech-to-Text",
            "credential_fields": ["credentials_path", "credentials_json", "location"],
            "default_model": "latest_long",
            "languages": [
                _language("en-US", "English (US)"),
                _language("en-IN", "English (India)"),
                _language("hi-IN", "Hindi (India)"),
            ],
        },
        {
            "provider": "openai",
            "label": "OpenAI Realtime STT",
            "credential_fields": ["api_key"],
            "option_fields": ["base_url"],
            "default_model": "gpt-4o-transcribe",
            "languages": [
                _language("en-US", "English (US)"),
                _language("en-IN", "English (India)"),
                _language("hi-IN", "Hindi (India)"),
            ],
        },
    ],
    "tts": [
        {
            "provider": "google",
            "label": "Google Cloud TTS",
            "voice_selector": "gender_or_voice_id",
            "credential_fields": ["project_id", "credentials_path", "credentials_json"],
            "option_fields": ["mode"],
            "supported_sample_rates": [16000, 24000],
            "default_language": "hi-IN",
            "default_voice_female": "hi-IN-Chirp3-HD-Pulcherrima",
            "default_voice_male": "hi-IN-Chirp3-HD-Puck",
            "languages": [
                _language("hi-IN", "Hindi (India)"),
                _language("en-IN", "English (India)"),
                _language("en-US", "English (US)"),
            ],
            "voices": [
                _voice("hi-IN-Chirp3-HD-Achernar", "Achernar", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Achird", "Achird", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Algenib", "Algenib", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Algieba", "Algieba", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Alnilam", "Alnilam", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Aoede", "Aoede", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Autonoe", "Autonoe", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Callirrhoe", "Callirrhoe", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Charon", "Charon", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Despina", "Despina", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Enceladus", "Enceladus", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Erinome", "Erinome", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Fenrir", "Fenrir", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Gacrux", "Gacrux", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Iapetus", "Iapetus", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Kore", "Kore", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Laomedeia", "Laomedeia", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Leda", "Leda", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Orus", "Orus", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Puck", "Puck", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Pulcherrima", "Pulcherrima", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Rasalgethi", "Rasalgethi", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Sadachbia", "Sadachbia", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Sadaltager", "Sadaltager", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Schedar", "Schedar", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Sulafat", "Sulafat", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Umbriel", "Umbriel", "male", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Vindemiatrix", "Vindemiatrix", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Zephyr", "Zephyr", "female", "hi-IN"),
                _voice("hi-IN-Chirp3-HD-Zubenelgenubi", "Zubenelgenubi", "male", "hi-IN"),
            ],
        },
        {
            "provider": "gemini_live",
            "label": "Gemini Live Native Audio",
            "voice_selector": "voice_id",
            "credential_fields": ["api_key"],
            "supported_sample_rates": [24000],
            "default_language": "hi-IN",
            "default_voice_female": "Kore",
            "default_voice_male": "Charon",
            "languages": [
                _language("hi-IN", "Hindi (India)"),
                _language("en-IN", "English (India)"),
                _language("en-US", "English (US)"),
            ],
            "voices": [
                _voice("Charon", "Charon", "male", "hi-IN"),
                _voice("Kore", "Kore", "female", "hi-IN"),
                _voice("Charon", "Charon", "male", "en-IN"),
                _voice("Kore", "Kore", "female", "en-IN"),
                _voice("Charon", "Charon", "male", "en-US"),
                _voice("Kore", "Kore", "female", "en-US"),
            ],
            "native_audio": True,
            "requires_llm_provider": ["gemini_live"],
            "supports_image_input": True,
            "supports_test_console": True,
        },
        {
            "provider": "kokoro",
            "label": "Kokoro",
            "voice_selector": "gender_or_voice_id",
            "credential_fields": ["base_url"],
            "option_fields": ["speed"],
            "supported_sample_rates": [16000, 24000],
            "default_language": "hi",
            "default_voice_female": "hf_alpha",
            "default_voice_male": "hm_omega",
            "languages": [
                _language("hi", "Hindi"),
                _language("en", "English"),
            ],
            "voices": [
                _voice("hf_alpha", "Alpha", "female", "hi"),
                _voice("hf_beta", "Beta", "female", "hi"),
                _voice("hm_omega", "Omega", "male", "hi"),
                _voice("hm_psi", "Psi", "male", "hi"),
            ],
        },
        {
            "provider": "indic_tts",
            "label": "Indic HTTP TTS",
            "voice_selector": "gender_or_voice_id",
            "credential_fields": ["base_url"],
            "option_fields": ["emotion"],
            "supported_sample_rates": [16000],
            "default_language": "hi",
            "default_voice_female": "female",
            "default_voice_male": "male",
            "languages": [
                _language("hi", "Hindi"),
                _language("en", "English"),
            ],
            "voices": [
                _voice("female", "Female", "female", "hi"),
                _voice("male", "Male", "male", "hi"),
            ],
        },
        {
            "provider": "deepgram",
            "label": "Deepgram Aura",
            "voice_selector": "voice_id",
            "credential_fields": ["api_key"],
            "supported_sample_rates": [24000],
            "default_language": "en-US",
            "default_voice_female": "aura-asteria-en",
            "default_voice_male": "aura-helios-en",
            "languages": [
                _language("en-US", "English (US)"),
            ],
            "voices": [
                _voice("aura-asteria-en", "Asteria", "female", "en-US"),
                _voice("aura-helios-en", "Helios", "male", "en-US"),
            ],
        },
        {
            "provider": "openai",
            "label": "OpenAI TTS",
            "voice_selector": "voice_id",
            "credential_fields": ["api_key"],
            "supported_sample_rates": [24000],
            "default_language": "en",
            "default_voice_female": "alloy",
            "default_voice_male": "alloy",
            "languages": [
                _language("en", "English"),
                _language("hi", "Hindi"),
            ],
            "voices": [
                _voice("alloy", "Alloy", "neutral", "en"),
            ],
        },
    ],
}


def _decorate_catalog(catalog: dict) -> dict:
    for modality in ("llm", "stt", "tts"):
        providers = catalog.get(modality)
        if not isinstance(providers, list):
            continue
        for item in providers:
            provider = item.get("provider", "")
            if "image" not in item:
                item["image"] = f"/static/providers/{provider}.png"
            item.setdefault("supports_test_console", True)
            item.setdefault("native_audio", False)
            item.setdefault("supports_image_input", False)
            item.setdefault("credential_validation", {"required_fields": item.get("credential_fields", [])})
    return catalog


def get_provider_catalog() -> dict:
    return _decorate_catalog(deepcopy(PROVIDER_CATALOG))


@router.get("/catalog")
async def provider_catalog():
    return get_provider_catalog()


@router.get("/catalog/{modality}/{provider}/voices")
async def provider_voices(
    modality: str,
    provider: str,
    language: str | None = Query(default=None),
    gender: str | None = Query(default=None),
):
    modality_key = modality.lower()
    provider_key = provider.lower()
    providers = PROVIDER_CATALOG.get(modality_key)
    if not isinstance(providers, list):
        raise HTTPException(status_code=404, detail="Unknown modality")

    record = next((item for item in providers if item["provider"] == provider_key), None)
    if not record:
        raise HTTPException(status_code=404, detail="Unknown provider")

    voices = record.get("voices", [])
    if language:
        voices = [voice for voice in voices if voice["language"] == language]
    if gender:
        voices = [voice for voice in voices if voice["gender"] == gender]
    return {
        "provider": provider_key,
        "modality": modality_key,
        "voices": voices,
    }
