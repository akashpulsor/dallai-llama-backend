CREATE TABLE pstn_channel_bundles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sip_trunk_id UUID REFERENCES sip_trunks(id),

    total_channels INT NOT NULL,
    inbound_channels INT NOT NULL,
    outbound_channels INT NOT NULL,
    active_channels INT DEFAULT 0,
    direction VARCHAR(20) DEFAULT 'BOTH',
    product_code VARCHAR(50),
    subscription_id UUID,
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, EXHAUSTED, SUSPENDED

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_channel_tenant ON pstn_channel_bundles(tenant_id);
UPDATE pstn_channel_bundles SET direction = 'BOTH' WHERE direction IS NULL;
-- ============================================================
-- V4__add_subscriptions_table.sql
-- ============================================================

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id),
    plan_id UUID NOT NULL REFERENCES plans(id),

    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',

    did_id UUID,
    sip_endpoint_id UUID,
    channel_bundle_id UUID,
    plan_assignment_id UUID,
    tenant_sip_trunk_id UUID,

    did_provisioned BOOLEAN DEFAULT FALSE,
    sip_endpoint_created BOOLEAN DEFAULT FALSE,
    channels_allocated BOOLEAN DEFAULT FALSE,

    agent_seats INT NOT NULL,
    included_minutes INT DEFAULT 0,

    subscribed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMP,
    expires_at TIMESTAMP,
    suspended_at TIMESTAMP,
    cancelled_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    version BIGINT DEFAULT 0,

    CONSTRAINT uk_subscription_tenant_product UNIQUE(tenant_id, product_id)
);

CREATE INDEX idx_subscription_tenant ON subscriptions(tenant_id);
CREATE INDEX idx_subscription_status ON subscriptions(status);

-- ============================================================
-- V7__create_tenant_sip_trunks.sql
-- ============================================================

CREATE TABLE tenant_sip_trunks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    subscription_id UUID,

    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    password_plain VARCHAR(100),

    realm VARCHAR(100) NOT NULL DEFAULT 'dalaillama.in',
    domain VARCHAR(100) NOT NULL DEFAULT 'sip.dalaillama.in',
    port INT NOT NULL DEFAULT 5060,
    transport VARCHAR(10) DEFAULT 'UDP',

    max_concurrent_calls INT DEFAULT 10,
    active BOOLEAN DEFAULT TRUE,
    synced_to_kamailio BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX idx_tenant_trunk_tenant ON tenant_sip_trunks(tenant_id);
CREATE INDEX idx_tenant_trunk_username ON tenant_sip_trunks(username);
