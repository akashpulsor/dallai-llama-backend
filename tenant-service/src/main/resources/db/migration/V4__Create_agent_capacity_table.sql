CREATE TABLE agent_capacities (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    licensed_agents INT DEFAULT 5,
    active_agents INT DEFAULT 0,
    licensed_supervisors INT DEFAULT 1,
    active_supervisors INT DEFAULT 0,
    max_concurrent_logins INT DEFAULT 5,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_capacity_tenant UNIQUE (tenant_id)
);