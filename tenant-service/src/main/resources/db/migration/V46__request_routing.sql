-- "Request a video" from the landing page names no creator: the request is routed to an active
-- creator. An inquiry therefore starts without one (tenant_id NULL) until it is assigned, offered
-- and accepted, or assigned by ops.
ALTER TABLE lead_brand_inquiry ALTER COLUMN tenant_id DROP NOT NULL;
-- CREATOR: sent from a creator's page (as before). LANDING: sent to Dalaillama, routed.
ALTER TABLE lead_brand_inquiry ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'CREATOR';
ALTER TABLE lead_brand_inquiry ADD CONSTRAINT lead_brand_inquiry_source_ck CHECK (source IN ('CREATOR', 'LANDING'));
-- The brand's industry for this video, which the router matches against creators' industries.
ALTER TABLE lead_brand_inquiry ADD COLUMN industry VARCHAR(32);
ALTER TABLE lead_brand_inquiry ADD CONSTRAINT lead_brand_inquiry_creator_ck CHECK (tenant_id IS NOT NULL OR source = 'LANDING');

-- How routed requests are handed out; one row, changed by ops.
CREATE TABLE request_routing_setting (
    id         SMALLINT    PRIMARY KEY,
    strategy   VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT request_routing_setting_one_ck CHECK (id = 1),
    CONSTRAINT request_routing_setting_strategy_ck CHECK (strategy IN ('ROUND_ROBIN_BY_INDUSTRY', 'OFFER_TO_ALL', 'OPS_ASSIGNS'))
);
INSERT INTO request_routing_setting (id, strategy) VALUES (1, 'ROUND_ROBIN_BY_INDUSTRY');

-- Creators who take routed requests (switched on by ops), and when each last got one, for
-- round-robin.
CREATE TABLE request_routing_creator (
    tenant_id      UUID        PRIMARY KEY,
    accepting      BOOLEAN     NOT NULL DEFAULT FALSE,
    last_routed_at TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT request_routing_creator_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE
);

-- Who a routed request went to and what they did. OFFERED: waiting for the creator to accept
-- (offer to all). ASSIGNED: theirs. DECLINED: they passed; never offered to them again.
-- WITHDRAWN: someone else took it.
CREATE TABLE lead_inquiry_offer (
    inquiry_id  UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    status      VARCHAR(16) NOT NULL,
    offered_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answered_at TIMESTAMPTZ,
    CONSTRAINT lead_inquiry_offer_pk PRIMARY KEY (inquiry_id, tenant_id),
    CONSTRAINT lead_inquiry_offer_inquiry_fk FOREIGN KEY (inquiry_id) REFERENCES lead_brand_inquiry (id) ON DELETE CASCADE,
    CONSTRAINT lead_inquiry_offer_profile_fk FOREIGN KEY (tenant_id) REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT lead_inquiry_offer_status_ck CHECK (status IN ('OFFERED', 'ASSIGNED', 'DECLINED', 'WITHDRAWN'))
);
CREATE INDEX ix_lead_inquiry_offer_creator ON lead_inquiry_offer (tenant_id, status);
