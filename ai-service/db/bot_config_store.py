"""Persistence helpers for tenant bot configuration bundles."""
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select

from db import database
from db.models import BotConfigRecord


def _clean_dict(value: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    cleaned = {k: v for k, v in value.items() if v not in (None, "", [], {})}
    return cleaned or None


def _to_dict(record: BotConfigRecord, *, include_secrets: bool) -> dict[str, Any]:
    provider_credentials = record.provider_credentials_json or {}
    if not include_secrets:
        provider_credentials = {
            provider: {
                key: "***" if value not in (None, "", [], {}) else value
                for key, value in values.items()
            }
            for provider, values in provider_credentials.items()
            if isinstance(values, dict)
        }

    return {
        "id": record.id,
        "tenant_id": record.tenant_id,
        "name": record.name,
        "description": record.description,
        "is_active": record.is_active,
        "llm_provider": record.llm_provider,
        "llm_model": record.llm_model,
        "stt_provider": record.stt_provider,
        "stt_model": record.stt_model,
        "tts_provider": record.tts_provider,
        "tts_model": record.tts_model,
        "tts_voice": record.tts_voice,
        "tts_gender": record.tts_gender,
        "language": record.language,
        "provider_configs": record.provider_configs_json or {},
        "provider_credentials": provider_credentials,
        "ui_state": record.ui_state_json or {},
        "metadata": record.metadata_json or {},
        "created_at": record.created_at,
        "updated_at": record.updated_at,
    }


async def create_bot_config(payload: dict[str, Any]) -> dict[str, Any]:
    if database.SessionLocal is None:
        raise RuntimeError("Database is not configured")

    now = datetime.now(timezone.utc)
    record = BotConfigRecord(
        tenant_id=payload["tenant_id"],
        name=payload["name"],
        description=payload.get("description"),
        is_active=payload.get("is_active", True),
        llm_provider=payload.get("llm_provider"),
        llm_model=payload.get("llm_model"),
        stt_provider=payload.get("stt_provider"),
        stt_model=payload.get("stt_model"),
        tts_provider=payload.get("tts_provider"),
        tts_model=payload.get("tts_model"),
        tts_voice=payload.get("tts_voice"),
        tts_gender=payload.get("tts_gender"),
        language=payload.get("language"),
        provider_configs_json=_clean_dict(payload.get("provider_configs")),
        provider_credentials_json=_clean_dict(payload.get("provider_credentials")),
        ui_state_json=_clean_dict(payload.get("ui_state")),
        metadata_json=_clean_dict(payload.get("metadata")),
        created_at=now,
        updated_at=now,
    )

    async with database.get_session() as session:
        session.add(record)
        await session.commit()
        await session.refresh(record)
        return _to_dict(record, include_secrets=True)


async def update_bot_config(config_id: str, payload: dict[str, Any]) -> dict[str, Any] | None:
    if database.SessionLocal is None:
        raise RuntimeError("Database is not configured")

    async with database.get_session() as session:
        record = await session.get(BotConfigRecord, config_id)
        if record is None:
            return None

        for field in (
            "tenant_id",
            "name",
            "description",
            "is_active",
            "llm_provider",
            "llm_model",
            "stt_provider",
            "stt_model",
            "tts_provider",
            "tts_model",
            "tts_voice",
            "tts_gender",
            "language",
        ):
            if field in payload:
                setattr(record, field, payload[field])

        if "provider_configs" in payload:
            record.provider_configs_json = _clean_dict(payload.get("provider_configs"))
        if "provider_credentials" in payload:
            record.provider_credentials_json = _clean_dict(payload.get("provider_credentials"))
        if "ui_state" in payload:
            record.ui_state_json = _clean_dict(payload.get("ui_state"))
        if "metadata" in payload:
            record.metadata_json = _clean_dict(payload.get("metadata"))

        record.updated_at = datetime.now(timezone.utc)
        await session.commit()
        await session.refresh(record)
        return _to_dict(record, include_secrets=True)


async def get_bot_config(config_id: str, *, include_secrets: bool = False) -> dict[str, Any] | None:
    if database.SessionLocal is None:
        raise RuntimeError("Database is not configured")

    async with database.get_session() as session:
        record = await session.get(BotConfigRecord, config_id)
        if record is None:
            return None
        return _to_dict(record, include_secrets=include_secrets)


async def list_bot_configs(
    tenant_id: str,
    *,
    active_only: bool = False,
    include_secrets: bool = False,
) -> list[dict[str, Any]]:
    if database.SessionLocal is None:
        raise RuntimeError("Database is not configured")

    async with database.get_session() as session:
        query = select(BotConfigRecord).where(BotConfigRecord.tenant_id == tenant_id)
        if active_only:
            query = query.where(BotConfigRecord.is_active.is_(True))
        query = query.order_by(BotConfigRecord.updated_at.desc(), BotConfigRecord.created_at.desc())
        rows = (await session.execute(query)).scalars().all()
        return [_to_dict(row, include_secrets=include_secrets) for row in rows]
