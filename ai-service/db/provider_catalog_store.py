"""Database-backed provider catalog helpers with seeded defaults."""
from copy import deepcopy
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select

from db import database
from db.models import ProviderCatalogEntry


def _language(code: str, label: str) -> dict[str, str]:
    return {"code": code, "label": label}


def _voice(voice_id: str, label: str, gender: str, language: str) -> dict[str, str]:
    return {"id": voice_id, "label": label, "gender": gender, "language": language}


CATALOG_STORAGE_SCHEMA = {
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
}


SEED_PROVIDER_CATALOG: dict[str, list[dict[str, Any]] | dict[str, Any]] = {
    "storage_schema": CATALOG_STORAGE_SCHEMA,
    "llm": [
        {
            "provider": "openai",
            "label": "OpenAI",
            "credential_fields": ["api_key"],
            "default_model": "gpt-4o-mini",
            "languages": [_language("multi", "Multilingual")],
            "selection_rules": {"next_required": ["stt_provider", "tts_provider"]},
        },
        {
            "provider": "ollama",
            "label": "Ollama",
            "credential_fields": ["base_url"],
            "default_model": "gemma2:2b",
            "languages": [_language("multi", "Model dependent")],
            "selection_rules": {"next_required": ["stt_provider", "tts_provider"]},
        },
        {
            "provider": "google",
            "label": "Google Gemini API",
            "credential_fields": ["api_key"],
            "default_model": "gemini-2.5-flash-lite",
            "languages": [_language("multi", "Multilingual")],
            "selection_rules": {"next_required": ["stt_provider", "tts_provider"]},
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
            "selection_rules": {
                "disables": ["stt_provider", "tts_provider", "stt_credentials", "tts_credentials"],
                "next_required": ["voice_id"],
            },
        },
        {
            "provider": "vertex",
            "label": "Google Vertex AI",
            "credential_fields": ["project_id", "location", "credentials_path", "credentials_json"],
            "default_model": "gemma3-4b-it",
            "languages": [_language("multi", "Multilingual")],
            "selection_rules": {"next_required": ["stt_provider", "tts_provider"]},
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
            "selection_rules": {"requires": ["llm_provider"], "next_required": ["tts_provider"]},
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
            "credential_validation": {
                "required_fields": ["location"],
                "one_of": [["credentials_path", "credentials_json"]],
            },
            "selection_rules": {"requires": ["llm_provider"], "next_required": ["tts_provider"]},
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
            "selection_rules": {"requires": ["llm_provider"], "next_required": ["tts_provider"]},
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
            "credential_validation": {
                "one_of": [["credentials_path", "credentials_json"]],
                "optional_fields": ["project_id"],
            },
            "selection_rules": {"requires": ["llm_provider", "stt_provider"], "next_required": ["language", "voice_id"]},
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
            "selection_rules": {"requires_values": {"llm_provider": ["gemini_live"]}, "next_required": ["voice_id"]},
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
            "languages": [_language("en-US", "English (US)")],
            "voices": [
                _voice("aura-asteria-en", "Asteria", "female", "en-US"),
                _voice("aura-helios-en", "Helios", "male", "en-US"),
            ],
            "selection_rules": {"requires": ["llm_provider", "stt_provider"], "next_required": ["voice_id"]},
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
            "languages": [_language("en", "English"), _language("hi", "Hindi")],
            "voices": [_voice("alloy", "Alloy", "neutral", "en")],
            "selection_rules": {"requires": ["llm_provider", "stt_provider"], "next_required": ["voice_id"]},
        },
    ],
}


def _decorate_catalog(catalog: dict[str, Any]) -> dict[str, Any]:
    for modality in ("llm", "stt", "tts"):
        providers = catalog.get(modality)
        if not isinstance(providers, list):
            continue
        for item in providers:
            provider = item.get("provider", "")
            item.setdefault("modality", modality)
            item.setdefault("image", f"/static/providers/{provider}.png")
            item.setdefault("logo_url", item["image"])
            item.setdefault("supports_test_console", True)
            item.setdefault("native_audio", False)
            item.setdefault("supports_image_input", False)
            item.setdefault("selection_rules", {})
            item.setdefault("credential_validation", {"required_fields": item.get("credential_fields", [])})
    return catalog


async def seed_provider_catalog() -> None:
    if database.SessionLocal is None:
        return

    async with database.get_session() as session:
        now = datetime.now(timezone.utc)
        for modality in ("llm", "stt", "tts"):
            for index, item in enumerate(SEED_PROVIDER_CATALOG.get(modality, [])):
                provider = item["provider"]
                result = await session.execute(
                    select(ProviderCatalogEntry).where(
                        ProviderCatalogEntry.modality == modality,
                        ProviderCatalogEntry.provider == provider,
                    )
                )
                existing = result.scalar_one_or_none()
                if existing is None:
                    session.add(
                        ProviderCatalogEntry(
                            modality=modality,
                            provider=provider,
                            label=item["label"],
                            is_active=item.get("is_active", True),
                            sort_order=index,
                            config_json=deepcopy(item),
                            created_at=now,
                            updated_at=now,
                        )
                    )
                else:
                    existing.label = item["label"]
                    existing.is_active = item.get("is_active", existing.is_active)
                    existing.sort_order = index
                    existing.config_json = deepcopy(item)
                    existing.updated_at = now
        await session.commit()


async def get_provider_catalog(*, active_only: bool = True) -> dict[str, Any]:
    if database.SessionLocal is None:
        return _decorate_catalog(deepcopy(SEED_PROVIDER_CATALOG))

    async with database.get_session() as session:
        rows = (await session.execute(select(ProviderCatalogEntry))).scalars().all()

    if not rows:
        return _decorate_catalog(deepcopy(SEED_PROVIDER_CATALOG))

    catalog: dict[str, Any] = {"storage_schema": deepcopy(CATALOG_STORAGE_SCHEMA), "llm": [], "stt": [], "tts": []}
    for row in sorted(rows, key=lambda value: (value.modality, value.sort_order, value.label.lower())):
        if active_only and not row.is_active:
            continue
        item = deepcopy(row.config_json or {})
        item["label"] = row.label
        item["provider"] = row.provider
        item["modality"] = row.modality
        item["is_active"] = row.is_active
        catalog.setdefault(row.modality, []).append(item)
    return _decorate_catalog(catalog)
