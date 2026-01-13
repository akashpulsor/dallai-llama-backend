CREATE TABLE pstn_channel_bundles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sip_trunk_id UUID REFERENCES sip_trunks(id),

    total_channels INT NOT NULL,
    inbound_channels INT NOT NULL,
    outbound_channels INT NOT NULL,
    active_channels INT DEFAULT 0,

    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, EXHAUSTED, SUSPENDED

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_channel_tenant ON pstn_channel_bundles(tenant_id);