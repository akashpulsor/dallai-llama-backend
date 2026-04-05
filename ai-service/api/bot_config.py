from collections import defaultdict
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from api.provider_catalog import get_provider_catalog
from auth.keycloak import require_auth
from db.bot_config_store import create_bot_config, get_bot_config, list_bot_configs, update_bot_config
from config import settings

router = APIRouter(prefix="/bot-configs", tags=["bot-configs"])


class BotConfigPayload(BaseModel):
    tenant_id: str = Field(..., min_length=1, max_length=128)
    name: str = Field(..., min_length=1, max_length=128)
    description: str | None = Field(default=None, max_length=2000)
    is_active: bool = True
    llm_provider: str | None = Field(default=None, max_length=64)
    llm_model: str | None = Field(default=None, max_length=128)
    stt_provider: str | None = Field(default=None, max_length=64)
    stt_model: str | None = Field(default=None, max_length=128)
    tts_provider: str | None = Field(default=None, max_length=64)
    tts_model: str | None = Field(default=None, max_length=128)
    tts_voice: str | None = Field(default=None, max_length=128)
    tts_gender: str | None = Field(default=None, max_length=32)
    language: str | None = Field(default=None, max_length=32)
    provider_configs: dict[str, Any] = Field(default_factory=dict)
    provider_credentials: dict[str, Any] = Field(default_factory=dict)
    ui_state: dict[str, Any] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class BotConfigUpdatePayload(BaseModel):
    tenant_id: str | None = Field(default=None, min_length=1, max_length=128)
    name: str | None = Field(default=None, min_length=1, max_length=128)
    description: str | None = Field(default=None, max_length=2000)
    is_active: bool | None = None
    llm_provider: str | None = Field(default=None, max_length=64)
    llm_model: str | None = Field(default=None, max_length=128)
    stt_provider: str | None = Field(default=None, max_length=64)
    stt_model: str | None = Field(default=None, max_length=128)
    tts_provider: str | None = Field(default=None, max_length=64)
    tts_model: str | None = Field(default=None, max_length=128)
    tts_voice: str | None = Field(default=None, max_length=128)
    tts_gender: str | None = Field(default=None, max_length=32)
    language: str | None = Field(default=None, max_length=32)
    provider_configs: dict[str, Any] | None = None
    provider_credentials: dict[str, Any] | None = None
    ui_state: dict[str, Any] | None = None
    metadata: dict[str, Any] | None = None


def _provider_usage(configs: list[dict[str, Any]]) -> dict[str, dict[str, list[str]]]:
    usage: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    for config in configs:
        for modality in ("llm", "stt", "tts"):
            provider = config.get(f"{modality}_provider")
            if provider:
                usage[modality][provider].append(config["id"])
    return {modality: dict(values) for modality, values in usage.items()}


def _step_rules(catalog: dict[str, Any]) -> dict[str, Any]:
    llm_native_audio = sorted(
        provider["provider"]
        for provider in catalog.get("llm", [])
        if provider.get("native_audio")
    )
    tts_requires_llm = {
        provider["provider"]: provider.get("requires_llm_provider", [])
        for provider in catalog.get("tts", [])
        if provider.get("requires_llm_provider")
    }
    return {
        "order": ["llm", "stt", "tts", "voice"],
        "rules": [
            {
                "if": {"llm_provider_in": llm_native_audio},
                "then": {
                    "disable": ["stt_provider", "tts_provider"],
                    "note": "Native-audio LLM providers can bypass external STT/TTS at runtime.",
                },
            },
            {
                "if": {"tts_provider_requires_llm": tts_requires_llm},
                "then": {
                    "note": "Some TTS providers are valid only with specific LLM providers.",
                },
            },
        ],
    }


def _decorate_dropdown(catalog: dict[str, Any], configs: list[dict[str, Any]]) -> dict[str, Any]:
    usage = _provider_usage(configs)
    for modality in ("llm", "stt", "tts"):
        for provider in catalog.get(modality, []):
            configured_ids = usage.get(modality, {}).get(provider["provider"], [])
            provider["configured_config_ids"] = configured_ids
            provider["is_configured"] = bool(configured_ids)
            provider["logo_url"] = provider.get("image")

    summaries = [
        {
            "id": config["id"],
            "tenant_id": config["tenant_id"],
            "name": config["name"],
            "description": config.get("description"),
            "is_active": config["is_active"],
            "llm_provider": config.get("llm_provider"),
            "stt_provider": config.get("stt_provider"),
            "tts_provider": config.get("tts_provider"),
            "language": config.get("language"),
            "tts_voice": config.get("tts_voice"),
            "tts_gender": config.get("tts_gender"),
            "updated_at": config.get("updated_at"),
        }
        for config in configs
    ]

    return {
        "tenant_id": configs[0]["tenant_id"] if configs else None,
        "saved_configs": summaries,
        "catalog": catalog,
        "selection_flow": _step_rules(catalog),
    }


def _ensure_database() -> None:
    if not settings.database_url:
        raise HTTPException(status_code=503, detail="Database is not configured")


def _runtime_view(config: dict[str, Any]) -> dict[str, Any]:
    return {
        "bot_config_id": config["id"],
        "tenant_id": config["tenant_id"],
        "is_active": config["is_active"],
        "llm_provider": config.get("llm_provider"),
        "llm_model": config.get("llm_model"),
        "stt_provider": config.get("stt_provider"),
        "stt_model": config.get("stt_model"),
        "tts_provider": config.get("tts_provider"),
        "tts_model": config.get("tts_model"),
        "tts_voice": config.get("tts_voice"),
        "tts_gender": config.get("tts_gender"),
        "provider_configs": config.get("provider_configs", {}),
        "tenant_provider_credentials": config.get("provider_credentials", {}),
        "bot": {
            "language": config.get("language"),
            "voice_provider": config.get("tts_provider"),
            "voice_id": config.get("tts_voice"),
            "voice_gender": config.get("tts_gender"),
        },
        "ui_state": config.get("ui_state", {}),
        "metadata": config.get("metadata", {}),
    }


@router.post("", summary="Create a tenant bot configuration")
async def create_bot_config_endpoint(
    payload: BotConfigPayload,
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    created = await create_bot_config(payload.model_dump())
    return {
        "status": "created",
        "bot_config_id": created["id"],
        "bot_config": {**created, "provider_credentials": {}},
    }


@router.put("/{config_id}", summary="Update a tenant bot configuration")
async def update_bot_config_endpoint(
    config_id: str,
    payload: BotConfigUpdatePayload,
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    updated = await update_bot_config(config_id, payload.model_dump(exclude_unset=True))
    if updated is None:
        raise HTTPException(status_code=404, detail="Bot config not found")
    return {
        "status": "updated",
        "bot_config_id": updated["id"],
        "bot_config": {**updated, "provider_credentials": {}},
    }


@router.get("/tenants/{tenant_id}/dropdown", summary="Get dropdown-ready bot configuration data for a tenant")
async def get_bot_config_dropdown(
    tenant_id: str,
    active_only: bool = Query(default=True),
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    configs = await list_bot_configs(tenant_id, active_only=active_only, include_secrets=False)
    catalog = await get_provider_catalog(active_only=active_only)
    payload = _decorate_dropdown(catalog, configs)
    payload["tenant_id"] = tenant_id
    payload["active_only"] = active_only
    return payload


@router.get("/{config_id}", summary="Get one tenant bot configuration")
async def get_bot_config_endpoint(
    config_id: str,
    include_secrets: bool = Query(default=False),
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    config = await get_bot_config(config_id, include_secrets=include_secrets)
    if config is None:
        raise HTTPException(status_code=404, detail="Bot config not found")
    return config


@router.get("/{config_id}/runtime", summary="Get one bot configuration in pipeline-ready shape")
async def get_bot_config_runtime(
    config_id: str,
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    config = await get_bot_config(config_id, include_secrets=True)
    if config is None:
        raise HTTPException(status_code=404, detail="Bot config not found")
    return _runtime_view(config)


@router.get("", summary="List tenant bot configurations")
async def list_bot_configs_endpoint(
    tenant_id: str = Query(..., min_length=1, max_length=128),
    active_only: bool = Query(default=False),
    include_secrets: bool = Query(default=False),
    _: dict[str, Any] = Depends(require_auth),
):
    _ensure_database()
    return {
        "tenant_id": tenant_id,
        "items": await list_bot_configs(
            tenant_id,
            active_only=active_only,
            include_secrets=include_secrets,
        ),
    }
