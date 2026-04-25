CREATE TABLE subscription_saga (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE REFERENCES subscriptions(id),
    tenant_id UUID NOT NULL,
    payment_id UUID,
    triggered_by_event_id UUID,

    current_step VARCHAR(64) NOT NULL,
    last_completed_step VARCHAR(64),
    failed_step VARCHAR(64),
    failure_reason TEXT,

    did_id UUID,
    sip_endpoint_id UUID,
    channel_bundle_id UUID,
    tenant_sip_trunk_id UUID,
    plan_assignment_id UUID,

    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retry_count INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_saga_status ON subscription_saga(current_step);
CREATE INDEX idx_saga_tenant ON subscription_saga(tenant_id);
CREATE INDEX idx_saga_event_id ON subscription_saga(triggered_by_event_id);