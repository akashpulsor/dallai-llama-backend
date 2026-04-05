from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query

from auth.keycloak import require_auth
from db.provider_catalog_store import get_provider_catalog as load_provider_catalog

router = APIRouter(prefix="/providers", tags=["providers"])


async def get_provider_catalog(*, active_only: bool = True) -> dict:
    return await load_provider_catalog(active_only=active_only)


def _build_selection_flow(catalog: dict) -> dict:
    native_audio_llms = [
        provider["provider"]
        for provider in catalog.get("llm", [])
        if provider.get("native_audio")
    ]
    tts_requires_llm = {
        provider["provider"]: provider.get("requires_llm_provider", [])
        for provider in catalog.get("tts", [])
        if provider.get("requires_llm_provider")
    }
    return {
        "order": ["llm", "stt", "tts", "voice"],
        "rules": [
            {
                "code": "native_audio_llm_bypasses_external_audio",
                "if": {"llm_provider_in": native_audio_llms},
                "then": {
                    "disable": ["stt_provider", "tts_provider", "stt_credentials", "tts_credentials"],
                    "require": ["voice_id"],
                },
            },
            {
                "code": "tts_provider_has_llm_dependency",
                "if": {"tts_provider_requires_llm": tts_requires_llm},
                "then": {
                    "validate": "selected_llm_provider must match allowed values for selected_tts_provider",
                },
            },
            {
                "code": "selection_sequence",
                "then": {
                    "require_step_order": ["llm_provider", "stt_provider", "tts_provider", "language", "voice_id"],
                },
            },
        ],
    }


@router.get("/catalog")
async def provider_catalog(
    active_only: bool = Query(default=True),
    _: dict[str, Any] = Depends(require_auth),
):
    catalog = await get_provider_catalog(active_only=active_only)
    catalog["selection_flow"] = _build_selection_flow(catalog)
    return catalog


@router.get("/catalog/{modality}/{provider}/voices")
async def provider_voices(
    modality: str,
    provider: str,
    language: str | None = Query(default=None),
    gender: str | None = Query(default=None),
    active_only: bool = Query(default=True),
    _: dict[str, Any] = Depends(require_auth),
):
    modality_key = modality.lower()
    provider_key = provider.lower()
    catalog = await get_provider_catalog(active_only=active_only)
    providers = catalog.get(modality_key)
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
        "selection_rules": record.get("selection_rules", {}),
        "credential_validation": record.get("credential_validation", {}),
    }
