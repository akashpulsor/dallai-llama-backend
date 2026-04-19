-- V11__payment_events.sql
-- Payment journey audit trail + subscription linkage

CREATE TABLE payment_events (
    id                  UUID PRIMARY KEY,
    payment_id          UUID        NOT NULL REFERENCES payments(id),
    tenant_id           UUID        NOT NULL,
    from_status         VARCHAR(20),
    to_status           VARCHAR(20) NOT NULL,
    gateway_order_id    VARCHAR(100),
    gateway_payment_id  VARCHAR(100),
    reason              VARCHAR(500),
    source              VARCHAR(50),
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pe_payment_id ON payment_events(payment_id);
CREATE INDEX idx_pe_tenant_id  ON payment_events(tenant_id);

-- Link payments to subscriptions for direct lookup
ALTER TABLE payments ADD COLUMN IF NOT EXISTS subscription_id UUID;
CREATE INDEX IF NOT EXISTS idx_payment_subscription ON payments(subscription_id);