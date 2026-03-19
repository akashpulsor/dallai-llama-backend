-- V2__agents_queues.sql

CREATE TABLE agents (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    username                VARCHAR(64) NOT NULL,      -- matches subscriber.username
    sip_domain              VARCHAR(100) NOT NULL,     -- matches subscriber.domain
    display_name            VARCHAR(100),
    extension               VARCHAR(10),
    email                   VARCHAR(100),
    role                    VARCHAR(20) DEFAULT 'AGENT',
    status                  VARCHAR(20) DEFAULT 'OFFLINE',
    max_concurrent_calls    INT DEFAULT 1,
    skills                  JSONB DEFAULT '[]',
    is_active               BOOLEAN DEFAULT TRUE,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, username),
    UNIQUE(tenant_id, extension)
);

CREATE TABLE queues (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    strategy                VARCHAR(30) DEFAULT 'ROUND_ROBIN',
    max_wait_seconds        INT DEFAULT 300,
    moh_file                VARCHAR(255),
    wrap_up_seconds         INT DEFAULT 15,
    is_active               BOOLEAN DEFAULT TRUE,
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);

CREATE TABLE queue_members (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_id                UUID NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
    agent_id                UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    priority                INT DEFAULT 1,
    penalty                 INT DEFAULT 0,
    UNIQUE(queue_id, agent_id)
);

-- Add these if they aren't already in your V2 script
CREATE INDEX idx_agents_tenant_status ON agents (tenant_id, status);
CREATE INDEX idx_agents_extension ON agents (extension);