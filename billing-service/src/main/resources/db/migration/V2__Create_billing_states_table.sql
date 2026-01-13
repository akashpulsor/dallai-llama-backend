CREATE TABLE billing_states (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE,

    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    grace_expires_at TIMESTAMP WITH TIME ZONE,
    block_reason VARCHAR(255),
    blocked_at TIMESTAMP WITH TIME ZONE,

    last_checked_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_billing_state_tenant ON billing_states(tenant_id);
CREATE INDEX idx_billing_state_state ON billing_states(state);
CREATE INDEX idx_billing_state_grace ON billing_states(state, grace_expires_at) WHERE state = 'GRACE';