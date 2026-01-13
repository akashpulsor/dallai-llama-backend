CREATE TABLE payments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    wallet_id UUID REFERENCES wallets(id),
    payment_method_id UUID REFERENCES payment_methods(id),

    amount DECIMAL(15,4) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',

    gateway VARCHAR(20) DEFAULT 'RAZORPAY',
    gateway_order_id VARCHAR(100),
    gateway_payment_id VARCHAR(100),
    gateway_signature VARCHAR(255),

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(500),

    description VARCHAR(500),
    metadata JSONB DEFAULT '{}',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_tenant ON payments(tenant_id, created_at DESC);
CREATE INDEX idx_payment_status ON payments(status);
CREATE INDEX idx_payment_gateway ON payments(gateway_order_id);