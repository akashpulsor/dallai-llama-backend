CREATE TABLE recording_policies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    mode VARCHAR(20) DEFAULT 'ALWAYS', -- ALWAYS, ON_DEMAND, DISABLED
    pause_allowed BOOLEAN DEFAULT true,
    stereo_recording BOOLEAN DEFAULT true,
    transcription_enabled BOOLEAN DEFAULT true,
    transcription_language VARCHAR(10) DEFAULT 'hi-IN',
    sentiment_analysis_enabled BOOLEAN DEFAULT false,
    storage_location VARCHAR(500),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_recording_tenant UNIQUE (tenant_id)
);