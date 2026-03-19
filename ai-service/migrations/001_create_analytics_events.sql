CREATE TABLE IF NOT EXISTS analytics_events (
    id VARCHAR(36) PRIMARY KEY,
    call_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    product_code VARCHAR(64) NOT NULL DEFAULT 'BASIC_PBX',
    speaker VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    utterance TEXT NULL,
    intent VARCHAR(128) NULL,
    intent_confidence DOUBLE PRECISION NULL,
    tone_label VARCHAR(64) NULL,
    tone_score DOUBLE PRECISION NULL,
    turn_index INTEGER NULL,
    metadata_json JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_analytics_events_call_id
    ON analytics_events (call_id);

CREATE INDEX IF NOT EXISTS ix_analytics_events_tenant_id
    ON analytics_events (tenant_id);

CREATE INDEX IF NOT EXISTS ix_analytics_events_event_type
    ON analytics_events (event_type);

CREATE INDEX IF NOT EXISTS ix_analytics_events_intent
    ON analytics_events (intent);

CREATE INDEX IF NOT EXISTS ix_analytics_events_created_at
    ON analytics_events (created_at);

CREATE INDEX IF NOT EXISTS ix_analytics_events_tenant_created
    ON analytics_events (tenant_id, created_at);

CREATE INDEX IF NOT EXISTS ix_analytics_events_tenant_speaker_created
    ON analytics_events (tenant_id, speaker, created_at);

CREATE INDEX IF NOT EXISTS ix_analytics_events_tenant_event_created
    ON analytics_events (tenant_id, event_type, created_at);
