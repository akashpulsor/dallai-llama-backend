CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    wallet_id UUID NOT NULL REFERENCES wallets(id),

    type VARCHAR(30) NOT NULL,

    amount DECIMAL(15,4) NOT NULL,
    balance_before DECIMAL(15,4) NOT NULL,
    balance_after DECIMAL(15,4) NOT NULL,

    reference VARCHAR(255),
    description VARCHAR(500),
    metadata JSONB DEFAULT '{}',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_txn_tenant ON transactions(tenant_id, created_at DESC);
CREATE INDEX idx_txn_wallet ON transactions(wallet_id, created_at DESC);
CREATE INDEX idx_txn_type ON transactions(type, created_at DESC);