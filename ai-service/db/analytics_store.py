"""Persistence and query helpers for analytics events."""
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import Select, func, select

from db import database
from db.models import AnalyticsEvent


async def store_event(
    *,
    call_id: str,
    tenant_id: str,
    product_code: str,
    speaker: str,
    event_type: str,
    utterance: str | None = None,
    intent: str | None = None,
    intent_confidence: float | None = None,
    tone_label: str | None = None,
    tone_score: float | None = None,
    turn_index: int | None = None,
    metadata_json: dict[str, Any] | None = None,
    created_at: datetime | None = None,
) -> None:
    if database.SessionLocal is None:
        return

    async with database.get_session() as session:
        session.add(
            AnalyticsEvent(
                call_id=call_id,
                tenant_id=tenant_id,
                product_code=product_code,
                speaker=speaker,
                event_type=event_type,
                utterance=utterance,
                intent=intent,
                intent_confidence=intent_confidence,
                tone_label=tone_label,
                tone_score=tone_score,
                turn_index=turn_index,
                metadata_json=metadata_json,
                created_at=created_at or datetime.now(timezone.utc),
            )
        )
        await session.commit()


def _base_query(tenant_id: str, speaker: str | None, start: datetime | None, end: datetime | None) -> Select:
    query = select(AnalyticsEvent).where(AnalyticsEvent.tenant_id == tenant_id)
    if speaker:
        query = query.where(AnalyticsEvent.speaker == speaker)
    if start:
        query = query.where(AnalyticsEvent.created_at >= start)
    if end:
        query = query.where(AnalyticsEvent.created_at <= end)
    return query


async def fetch_timeline(
    *,
    tenant_id: str,
    speaker: str | None = None,
    start: datetime | None = None,
    end: datetime | None = None,
) -> list[dict[str, Any]]:
    if database.SessionLocal is None:
        return []

    async with database.get_session() as session:
        query = _base_query(tenant_id, speaker, start, end).order_by(AnalyticsEvent.created_at.asc())
        rows = (await session.execute(query)).scalars().all()

    buckets: dict[str, dict[str, Any]] = defaultdict(
        lambda: {
            "count": 0,
            "intents": defaultdict(int),
            "tones": defaultdict(int),
            "avg_tone_score": 0.0,
            "tone_samples": 0,
        }
    )

    for row in rows:
        bucket = row.created_at.replace(second=0, microsecond=0).isoformat()
        item = buckets[bucket]
        item["count"] += 1
        if row.intent:
            item["intents"][row.intent] += 1
        if row.tone_label:
            item["tones"][row.tone_label] += 1
        if row.tone_score is not None:
            item["avg_tone_score"] += row.tone_score
            item["tone_samples"] += 1

    timeline = []
    for bucket in sorted(buckets.keys()):
        item = buckets[bucket]
        tone_samples = item.pop("tone_samples")
        avg_tone_score = item.pop("avg_tone_score")
        timeline.append(
            {
                "timestamp": bucket,
                "count": item["count"],
                "intents": dict(item["intents"]),
                "tones": dict(item["tones"]),
                "avg_tone_score": round(avg_tone_score / tone_samples, 3) if tone_samples else None,
            }
        )
    return timeline


async def fetch_summary(
    *,
    tenant_id: str,
    speaker: str | None = None,
    start: datetime | None = None,
    end: datetime | None = None,
) -> dict[str, Any]:
    if database.SessionLocal is None:
        return {"events": 0, "calls": 0, "top_intents": [], "top_tones": []}

    async with database.get_session() as session:
        base = _base_query(tenant_id, speaker, start, end).subquery()

        totals = await session.execute(
            select(func.count().label("events"), func.count(func.distinct(base.c.call_id)).label("calls"))
        )
        total_row = totals.one()

        intents = await session.execute(
            select(base.c.intent, func.count().label("count"))
            .where(base.c.intent.is_not(None))
            .group_by(base.c.intent)
            .order_by(func.count().desc())
            .limit(10)
        )
        tones = await session.execute(
            select(base.c.tone_label, func.count().label("count"))
            .where(base.c.tone_label.is_not(None))
            .group_by(base.c.tone_label)
            .order_by(func.count().desc())
            .limit(10)
        )

    return {
        "events": total_row.events,
        "calls": total_row.calls,
        "top_intents": [{"intent": intent, "count": count} for intent, count in intents.all()],
        "top_tones": [{"tone": tone, "count": count} for tone, count in tones.all()],
    }
