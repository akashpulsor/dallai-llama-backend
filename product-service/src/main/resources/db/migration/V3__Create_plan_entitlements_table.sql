CREATE TABLE plan_entitlements (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,

    -- Agent limits
    max_agents INT DEFAULT 5,
    max_supervisors INT DEFAULT 1,
    max_concurrent_logins INT DEFAULT 5,

    -- Channel limits
    max_pstn_channels INT DEFAULT 5,
    max_dids INT DEFAULT 2,

    -- Voice features
    inbound_enabled BOOLEAN DEFAULT true,
    outbound_enabled BOOLEAN DEFAULT true,
    recording_enabled BOOLEAN DEFAULT true,

    -- Analytics
    analytics_enabled BOOLEAN DEFAULT true,
    analytics_retention_days INT DEFAULT 30,

    -- AI features
    ai_stt_enabled BOOLEAN DEFAULT false,
    ai_llm_enabled BOOLEAN DEFAULT false,
    ai_bot_enabled BOOLEAN DEFAULT false,
    ai_sentiment_enabled BOOLEAN DEFAULT false,
    ai_tokens_per_month BIGINT DEFAULT 0,

    -- Queue/IVR limits
    max_queues INT DEFAULT 3,
    max_ivr_flows INT DEFAULT 2,

    -- Storage
    recording_storage_gb INT DEFAULT 10,
    recording_retention_days INT DEFAULT 30,

    -- Supervisor features
    barge_enabled BOOLEAN DEFAULT false,
    whisper_enabled BOOLEAN DEFAULT false,
    monitor_enabled BOOLEAN DEFAULT true,

    -- Additional features
    conference_enabled BOOLEAN DEFAULT false,
    voicemail_enabled BOOLEAN DEFAULT true,
    callback_enabled BOOLEAN DEFAULT false,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_entitlement_plan UNIQUE (plan_id)
);