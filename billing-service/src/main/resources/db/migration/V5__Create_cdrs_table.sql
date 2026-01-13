CREATE TABLE cdrs (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,

    call_id VARCHAR(100) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    from_number VARCHAR(30),
    to_number VARCHAR(30),

    did_id UUID,
    did_number VARCHAR(20),

    agent_id UUID,
    queue_id UUID,
    ivr_flow_id UUID,

    initiated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ringing_at TIMESTAMP WITH TIME ZONE,
    answered_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,

    duration_seconds INT DEFAULT 0,
    billable_seconds INT DEFAULT 0,

    status VARCHAR(20) NOT NULL,
    hangup_cause VARCHAR(50),

    rated BOOLEAN DEFAULT false,
    rate_plan_id UUID,
    destination_type VARCHAR(30),
    applied_rate DECIMAL(15,6),
    call_cost DECIMAL(15,4) DEFAULT 0,

    ai_stt_seconds INT DEFAULT 0,
    ai_llm_tokens INT DEFAULT 0,
    ai_cost DECIMAL(15,4) DEFAULT 0,

    total_cost DECIMAL(15,4) DEFAULT 0,

    metadata JSONB DEFAULT '{}',

    processed BOOLEAN DEFAULT false,
    processed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, initiated_at)
);

-- TimescaleDB hypertable (optional - use only if TimescaleDB is installed)
-- SELECT create_hypertable('cdrs', 'initiated_at', chunk_time_interval => INTERVAL '1 day');

CREATE INDEX idx_cdr_tenant ON cdrs(tenant_id, initiated_at DESC);
CREATE INDEX idx_cdr_call_id ON cdrs(call_id);
CREATE INDEX idx_cdr_processed ON cdrs(processed, initiated_at) WHERE processed = false;
CREATE UNIQUE INDEX idx_cdr_unique ON cdrs(call_id, tenant_id);