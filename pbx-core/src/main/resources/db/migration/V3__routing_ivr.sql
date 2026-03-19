-- V3__routing_ivr.sql

CREATE TABLE routing_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    priority                INT DEFAULT 0,
    match_type              VARCHAR(20) DEFAULT 'DID',   -- DID, CALLER_ID, TIME, ALL
    match_value             VARCHAR(100),
    action_type             VARCHAR(30) NOT NULL,         -- QUEUE, AGENT, IVR, AI_BOT, VOICEMAIL, EXTERNAL
    action_target           VARCHAR(200) NOT NULL,        -- queue UUID, agent UUID, IVR UUID, etc.
    time_condition          JSONB,                        -- {"days":[1,2,3,4,5],"start":"09:00","end":"17:00"}
    is_active               BOOLEAN DEFAULT TRUE,
    created_at              TIMESTAMP DEFAULT NOW()
);

CREATE TABLE ivr_flows (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    flow_json               JSONB NOT NULL,               -- Visual IVR builder output
    is_active               BOOLEAN DEFAULT TRUE,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);

-- Ensure fast lookup for active policies sorted by priority
CREATE INDEX idx_routing_lookup ON routing_policies (tenant_id, is_active, priority DESC);