CREATE TABLE provisioning_tasks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step VARCHAR(50),
    current_step_status VARCHAR(20),
    current_step_started_at TIMESTAMP WITH TIME ZONE,

    completed_steps JSONB DEFAULT '[]',
    step_results JSONB DEFAULT '{}',

    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    last_error TEXT,
    last_error_at TIMESTAMP WITH TIME ZONE,

    compensation_started_at TIMESTAMP WITH TIME ZONE,
    compensation_completed_at TIMESTAMP WITH TIME ZONE,
    compensated_steps JSONB DEFAULT '[]',

    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_prov_task_tenant ON provisioning_tasks(tenant_id);
CREATE INDEX idx_prov_task_status ON provisioning_tasks(status);