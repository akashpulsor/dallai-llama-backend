"""SQLAlchemy models for analytics persistence."""
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import DateTime, Float, Index, Integer, String, Text
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
