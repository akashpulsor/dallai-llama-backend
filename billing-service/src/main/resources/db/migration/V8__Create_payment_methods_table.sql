CREATE TABLE payment_methods (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,

    type VARCHAR(20) NOT NULL,
    display_name VARCHAR(100),
    masked_reference VARCHAR(50),

    gateway_token VARCHAR(500),
    gateway_customer_id VARCHAR(100),

    is_default BOOLEAN DEFAULT false,
    expires_at TIMESTAMP WITH TIME ZONE,

    active BOOLEAN DEFAULT true,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_pm_tenant ON payment_methods(tenant_id);