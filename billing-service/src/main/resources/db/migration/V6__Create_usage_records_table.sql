CREATE TABLE usage_records (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,

    metric VARCHAR(50) NOT NULL,
    quantity DECIMAL(15,4) NOT NULL,
    unit VARCHAR(20) NOT NULL,

    unit_cost DECIMAL(15,6),
    total_cost DECIMAL(15,4) NOT NULL,

    source_type VARCHAR(30),
    source_id UUID,
    description VARCHAR(255),

    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id, recorded_at)
);

CREATE INDEX idx_usage_tenant ON usage_records(tenant_id, recorded_at DESC);
CREATE INDEX idx_usage_metric ON usage_records(metric, recorded_at DESC);