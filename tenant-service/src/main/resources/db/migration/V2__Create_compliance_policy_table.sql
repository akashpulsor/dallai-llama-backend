CREATE TABLE compliance_policies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    consent_prompt_required BOOLEAN DEFAULT true,
    retention_days INT DEFAULT 90,
    data_region VARCHAR(10) DEFAULT 'IN',
    pii_pause_required BOOLEAN DEFAULT false,
    dnc_check_required BOOLEAN DEFAULT true,
    call_time_restriction_enabled BOOLEAN DEFAULT false,
    call_window_start TIME,
    call_window_end TIME,
    blocked_days VARCHAR(100), -- JSON array: ["SUNDAY"]

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_compliance_tenant UNIQUE (tenant_id)
);