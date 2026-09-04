-- Client pays to lock a reviewed creative package. Its own dedicated table (Payment is generic
-- across subscriptions/DID/project-funding) -- this money comes FROM a client, not a wallet
-- top-up. platform_base is the platform's take; creator_amount (the creator's margin) is credited
-- to the creator's wallet on success; settled=false marks the platform's take as not-yet-paid-out
-- (settlement processing is a later concern, tracked here as raw data).
CREATE TABLE client_review_payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    review_token VARCHAR(128) NOT NULL,
    platform_base NUMERIC(15,2) NOT NULL,
    creator_amount NUMERIC(15,2) NOT NULL,
    total_amount NUMERIC(15,2) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    gateway_order_id VARCHAR(128),
    gateway_payment_id VARCHAR(128),
    settled BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_client_review_payment_project ON client_review_payment(tenant_id, project_id);
CREATE UNIQUE INDEX idx_client_review_payment_order ON client_review_payment(gateway_order_id) WHERE gateway_order_id IS NOT NULL;
