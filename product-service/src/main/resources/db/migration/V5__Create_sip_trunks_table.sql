CREATE TABLE sip_trunks (
    id UUID PRIMARY KEY,
    tenant_id UUID, -- NULL for platform-level trunks

    name VARCHAR(100) NOT NULL,
    provider VARCHAR(30) NOT NULL, -- DIDWW, TATA, AIRTEL, CUSTOM

    -- Connection
    server VARCHAR(255) NOT NULL,
    port INT DEFAULT 5060,
    transport VARCHAR(10) DEFAULT 'UDP', -- UDP, TCP, TLS, WSS

    -- Authentication
    auth_type VARCHAR(20) DEFAULT 'IP', -- IP, CREDENTIAL, BOTH
    auth_username VARCHAR(100),
    auth_password_encrypted VARCHAR(500),
    allowed_ips VARCHAR(500), -- Comma-separated

    -- Codecs (JSON array)
    codecs JSONB DEFAULT '["G711A", "G711U", "G729"]',

    -- Limits
    max_concurrent_calls INT DEFAULT 100,
    max_calls_per_second INT DEFAULT 10,

    -- Status
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, ACTIVE, SUSPENDED, FAILED
    last_health_check TIMESTAMP WITH TIME ZONE,
    is_healthy BOOLEAN DEFAULT true,

    -- DIDWW specific
    didww_trunk_id VARCHAR(100),
    didww_sip_config_id VARCHAR(100),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_trunk_tenant ON sip_trunks(tenant_id);
CREATE INDEX idx_trunk_provider ON sip_trunks(provider);
CREATE INDEX idx_trunk_status ON sip_trunks(status);