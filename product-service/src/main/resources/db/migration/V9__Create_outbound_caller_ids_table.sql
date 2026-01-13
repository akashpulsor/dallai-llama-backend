CREATE TABLE outbound_caller_ids (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sip_trunk_id UUID REFERENCES sip_trunks(id),

    number VARCHAR(20) NOT NULL,
    display_name VARCHAR(100),
    verified BOOLEAN DEFAULT false,
    is_default BOOLEAN DEFAULT false,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_cli_tenant ON outbound_caller_ids(tenant_id);