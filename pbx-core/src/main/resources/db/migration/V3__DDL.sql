CREATE TABLE ivr_node (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    parent_node_id VARCHAR(128),
    prompt VARCHAR(512),
    bot_enabled BOOLEAN DEFAULT FALSE,
    transfer_number VARCHAR(64),
    is_final_node BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_ivr_node_tenant ON ivr_node(tenant_id);
CREATE UNIQUE INDEX idx_ivr_node_tenant_nodeid ON ivr_node(tenant_id, node_id);

CREATE TABLE ivr_dtmf_map (
    id BIGSERIAL PRIMARY KEY,
    node_id BIGINT NOT NULL REFERENCES ivr_node(id) ON DELETE CASCADE,
    dtmf_key VARCHAR(4) NOT NULL,
    next_node VARCHAR(128) NOT NULL
);
