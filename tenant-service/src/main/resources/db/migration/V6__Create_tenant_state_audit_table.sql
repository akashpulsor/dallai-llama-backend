CREATE TABLE tenant_state_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),

    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    old_substatus VARCHAR(50),
    new_substatus VARCHAR(50),

    trigger_type VARCHAR(30),
    trigger_source VARCHAR(100),
    trigger_reference VARCHAR(255),

    message VARCHAR(500),
    metadata JSONB,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_tenant ON tenant_state_audit(tenant_id);
CREATE INDEX idx_audit_created ON tenant_state_audit(created_at);