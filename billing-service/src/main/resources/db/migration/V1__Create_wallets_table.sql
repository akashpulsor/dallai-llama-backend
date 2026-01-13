CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE,

    balance DECIMAL(15,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    credit_limit DECIMAL(15,4) DEFAULT 0,
    low_balance_threshold DECIMAL(15,4) DEFAULT 500,

    -- Auto recharge
    auto_recharge_enabled BOOLEAN DEFAULT false,
    auto_recharge_threshold DECIMAL(15,4),
    auto_recharge_amount DECIMAL(15,4),
    default_payment_method_id UUID,

    -- Timestamps
    last_recharged_at TIMESTAMP WITH TIME ZONE,
    last_deducted_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_wallet_tenant ON wallets(tenant_id);
CREATE INDEX idx_wallet_balance ON wallets(balance);

