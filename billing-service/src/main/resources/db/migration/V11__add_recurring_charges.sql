-- V3__add_recurring_charges.sql
-- Add recurring charges table for auto-billing

CREATE TABLE recurring_charges (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,  -- PLATFORM_FEE, DID_RENTAL, AGENT_FEE
    amount DECIMAL(10,2) NOT NULL,
    frequency VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    next_charge_date DATE NOT NULL,
    last_charged_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, PAUSED, CANCELLED
    source_type VARCHAR(30),  -- DID, PLAN, AGENT
    source_id UUID,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

-- Indexes
CREATE INDEX idx_recurring_tenant ON recurring_charges(tenant_id);
CREATE INDEX idx_recurring_next_date ON recurring_charges(next_charge_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_recurring_status ON recurring_charges(status);
CREATE INDEX idx_recurring_source ON recurring_charges(tenant_id, source_type, source_id);

-- Comments
COMMENT ON TABLE recurring_charges IS 'Recurring charges for auto-billing (platform fee, DID rental, etc.)';
COMMENT ON COLUMN recurring_charges.type IS 'PLATFORM_FEE, DID_RENTAL, AGENT_FEE';
COMMENT ON COLUMN recurring_charges.frequency IS 'MONTHLY, WEEKLY, YEARLY';
COMMENT ON COLUMN recurring_charges.status IS 'ACTIVE, PAUSED, CANCELLED';