CREATE TABLE plan_assignments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plan_id UUID NOT NULL REFERENCES plans(id),

    effective_from TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    effective_to TIMESTAMP WITH TIME ZONE,
    active BOOLEAN DEFAULT true,

    -- Custom overrides for enterprise deals
    entitlement_overrides JSONB DEFAULT '{}',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_assignment_tenant ON plan_assignments(tenant_id);
CREATE INDEX idx_assignment_plan ON plan_assignments(plan_id);
CREATE INDEX idx_assignment_active ON plan_assignments(tenant_id, active) WHERE active = true;