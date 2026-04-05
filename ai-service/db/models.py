"""SQLAlchemy models for analytics and bot-config persistence."""
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, Float, Index, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import JSON


class Base(DeclarativeBase):
    pass


def _json_type():
    try:
        return JSONB
    except Exception:
        return JSON


class AnalyticsEvent(Base):
    __tablename__ = "analytics_events"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    call_id: Mapped[str] = mapped_column(String(128), index=True)
    tenant_id: Mapped[str] = mapped_column(String(128), index=True)
    product_code: Mapped[str] = mapped_column(String(64), default="BASIC_PBX")
    speaker: Mapped[str] = mapped_column(String(32), index=True)
    event_type: Mapped[str] = mapped_column(String(32), index=True)
    utterance: Mapped[str | None] = mapped_column(Text(), nullable=True)
    intent: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    intent_confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    tone_label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    tone_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    turn_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    metadata_json: Mapped[dict | None] = mapped_column(_json_type(), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        index=True,
    )

    __table_args__ = (
        Index("ix_analytics_events_tenant_created", "tenant_id", "created_at"),
        Index("ix_analytics_events_tenant_speaker_created", "tenant_id", "speaker", "created_at"),
        Index("ix_analytics_events_tenant_event_created", "tenant_id", "event_type", "created_at"),
    )


class BotConfigRecord(Base):
    __tablename__ = "bot_configs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    tenant_id: Mapped[str] = mapped_column(String(128), index=True)
    name: Mapped[str] = mapped_column(String(128))
    description: Mapped[str | None] = mapped_column(Text(), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    llm_provider: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    llm_model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    stt_provider: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    stt_model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    tts_provider: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    tts_model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    tts_voice: Mapped[str | None] = mapped_column(String(128), nullable=True)
    tts_gender: Mapped[str | None] = mapped_column(String(32), nullable=True)
    language: Mapped[str | None] = mapped_column(String(32), nullable=True)
    provider_configs_json: Mapped[dict | None] = mapped_column(_json_type(), nullable=True)
    provider_credentials_json: Mapped[dict | None] = mapped_column(_json_type(), nullable=True)
    ui_state_json: Mapped[dict | None] = mapped_column(_json_type(), nullable=True)
    metadata_json: Mapped[dict | None] = mapped_column(_json_type(), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        index=True,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        index=True,
    )

    __table_args__ = (
        Index("ix_bot_configs_tenant_active", "tenant_id", "is_active"),
        Index("ix_bot_configs_tenant_updated", "tenant_id", "updated_at"),
    )


class ProviderCatalogEntry(Base):
    __tablename__ = "provider_catalog_entries"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    modality: Mapped[str] = mapped_column(String(32), index=True)
    provider: Mapped[str] = mapped_column(String(64), index=True)
    label: Mapped[str] = mapped_column(String(128))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    config_json: Mapped[dict] = mapped_column(_json_type())
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        index=True,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        index=True,
    )

    __table_args__ = (
        Index("ix_provider_catalog_modality_provider", "modality", "provider"),
        Index("ix_provider_catalog_modality_active_sort", "modality", "is_active", "sort_order"),
    )
