CREATE TABLE dids (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sip_trunk_id UUID REFERENCES sip_trunks(id),

    -- Number info
    number VARCHAR(20) NOT NULL, -- E.164 format
    display_number VARCHAR(30), -- Formatted for display

    -- Location
    country VARCHAR(2) NOT NULL,
    region VARCHAR(100),
    city VARCHAR(100),

    -- DIDWW references
    didww_did_id VARCHAR(100),
    didww_trunk_group_id VARCHAR(100),

    -- Status
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, PROVISIONING, ACTIVE, SUSPENDED, RELEASING, RELEASED

    -- Billing
    monthly_rental DECIMAL(10,2),
    currency VARCHAR(3) DEFAULT 'INR',

    -- Timestamps
    provisioned_at TIMESTAMP WITH TIME ZONE,
    released_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_did_number ON dids(number) WHERE status != 'RELEASED';
CREATE INDEX idx_did_tenant ON dids(tenant_id);
CREATE INDEX idx_did_status ON dids(status);