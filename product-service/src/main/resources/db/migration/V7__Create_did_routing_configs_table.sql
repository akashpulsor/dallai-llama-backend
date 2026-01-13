CREATE TABLE did_routing_configs (
    id UUID PRIMARY KEY,
    did_id UUID NOT NULL REFERENCES dids(id) ON DELETE CASCADE,

    -- Routing target
    routing_target_type VARCHAR(30) NOT NULL, -- IVR, QUEUE, AGENT, BOT, VOICEMAIL, EXTERNAL
    routing_target_id VARCHAR(100), -- ID of the target (IVR flow ID, queue ID, etc.)

    -- Fallback
    fallback_target_type VARCHAR(30),
    fallback_target_id VARCHAR(100),

    -- Options
    recording_enabled BOOLEAN DEFAULT true,
    transcription_enabled BOOLEAN DEFAULT false,
    sentiment_enabled BOOLEAN DEFAULT false,

    -- Business hours
    business_hours_only BOOLEAN DEFAULT false,
    business_hours_start TIME,
    business_hours_end TIME,
    after_hours_target_type VARCHAR(30),
    after_hours_target_id VARCHAR(100),

    -- Advanced
    max_ring_time INT DEFAULT 30,
    priority INT DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_routing_did UNIQUE (did_id)
);