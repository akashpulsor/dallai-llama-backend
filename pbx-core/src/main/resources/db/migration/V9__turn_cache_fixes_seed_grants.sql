-- V9__turn_cache_fixes_seed_grants.sql
--
-- DESIGN DECISION: NO tenant_telecom_config table.
--
-- TenantApp in tenant-service already holds all 80+ provisioning fields
-- (channels, features, rates, SIP config, AI flags, deployment model, etc.)
-- Duplicating that here creates a sync nightmare.
--
-- Instead, PBX-Core:
--   1. Calls tenant-service GET /api/v1/tenant-apps/by-did/{didNumber}
--      or GET /api/v1/tenant-apps/{subscriptionId} at runtime
--   2. Caches the response in Redis (key: "tenant:config:{tenantId}", TTL 5min)
--   3. Kamailio auth hot path reads from Redis cache, falls back to HTTP
--
-- The only PBX-Core-owned tables are operational ones that tenant-service
-- has no business knowing about: agents, queues, CDR, campaigns, bots, etc.

-- ════════════════════════════════════════════════════════════════
-- 1. TENANT_CONFIG_CACHE — Thin DB-backed cache for call-auth
--    Primary store is Redis. This table is:
--    - Fallback when Redis is cold/down
--    - Audit trail of what config was active when
--    - Rebuilt from tenant-service on demand
-- ════════════════════════════════════════════════════════════════

CREATE TABLE tenant_config_cache (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID            NOT NULL UNIQUE,
    subscription_id     UUID            NOT NULL,
    did_number          VARCHAR(20),
    sip_domain          VARCHAR(100)    NOT NULL,
    product_code        VARCHAR(30)     NOT NULL,
    namespace           VARCHAR(100)    NOT NULL,

    -- Fields PBX-Core needs for call authorization (subset of TenantApp)
    max_channels        INT             DEFAULT 30,
    max_inbound         INT,
    max_outbound        INT,
    ai_enabled          BOOLEAN         DEFAULT FALSE,
    recording_enabled   BOOLEAN         DEFAULT FALSE,
    deployment_model    VARCHAR(20)     DEFAULT 'SHARED',
    status              VARCHAR(20)     DEFAULT 'ACTIVE',

    -- Full TenantApp snapshot as JSON (everything PBX-Core might need)
    config_snapshot     JSONB           NOT NULL DEFAULT '{}',

    cached_at           TIMESTAMP       DEFAULT NOW(),
    expires_at          TIMESTAMP       NOT NULL
);

CREATE INDEX idx_tcc_did       ON tenant_config_cache (did_number) WHERE did_number IS NOT NULL;
CREATE INDEX idx_tcc_domain    ON tenant_config_cache (sip_domain);
CREATE INDEX idx_tcc_expires   ON tenant_config_cache (expires_at);

-- ════════════════════════════════════════════════════════════════
-- 2. TURN_CREDENTIALS_CACHE — DB-backed TURN credential store
--    Primary store is Redis (fast path for Agent UI).
--    This table serves as audit trail + Redis rebuild source.
-- ════════════════════════════════════════════════════════════════

CREATE TABLE turn_credentials_cache (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    username        VARCHAR(200)    NOT NULL,
    password        VARCHAR(200)    NOT NULL,
    turn_url        VARCHAR(200),
    turns_url       VARCHAR(200),
    stun_url        VARCHAR(200),
    ttl             INT             DEFAULT 3600,
    expires_at      TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       DEFAULT NOW()
);

CREATE INDEX idx_turn_tenant    ON turn_credentials_cache (tenant_id);
CREATE INDEX idx_turn_expires   ON turn_credentials_cache (expires_at);

-- ════════════════════════════════════════════════════════════════
-- 3. V7 FIX — Drop redundant index, add missing index
-- ════════════════════════════════════════════════════════════════

-- idx_agents_extension on (tenant_id, extension) is redundant:
-- UNIQUE(tenant_id, extension) in V2 already creates an implicit index.
DROP INDEX IF EXISTS idx_agents_extension;

-- Supervisor dashboard queries filter by (tenant_id, status) constantly.
CREATE INDEX idx_call_records_status ON call_records (tenant_id, status);

-- ════════════════════════════════════════════════════════════════
-- 4. V8 FIXES — Add FK constraints for dangling UUIDs
-- ════════════════════════════════════════════════════════════════

ALTER TABLE campaigns
    ADD CONSTRAINT fk_campaigns_after_hours_bot
    FOREIGN KEY (after_hours_bot_id) REFERENCES bots(id) ON DELETE SET NULL;

ALTER TABLE campaign_contacts
    ADD CONSTRAINT fk_contacts_assigned_agent
    FOREIGN KEY (assigned_agent_id) REFERENCES agents(id) ON DELETE SET NULL;

-- ════════════════════════════════════════════════════════════════
-- 5. SEED DATA — Default dispatcher + domain for local dev
-- ════════════════════════════════════════════════════════════════

INSERT INTO dispatcher (setid, destination, flags, priority, description)
VALUES (1, 'sip:127.0.0.1:5080', 0, 0, 'FreeSWITCH - default')
ON CONFLICT (setid, destination) DO NOTHING;

INSERT INTO domain (domain, tenant_id, is_active)
VALUES ('sip.localhost', '00000000-0000-0000-0000-000000000000', TRUE)
ON CONFLICT (domain) DO NOTHING;

-- ════════════════════════════════════════════════════════════════
-- 6. KAMAILIO READONLY ROLE GRANTS
--    CREATE USER must happen outside Flyway (kubectl / Helm init).
--    This grants permissions if the role already exists.
-- ════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kamailio_readonly') THEN

        GRANT USAGE ON SCHEMA public TO kamailio_readonly;

        GRANT SELECT ON subscriber      TO kamailio_readonly;
        GRANT SELECT ON domain          TO kamailio_readonly;
        GRANT SELECT ON domain_attrs    TO kamailio_readonly;
        GRANT SELECT ON dispatcher      TO kamailio_readonly;
        GRANT SELECT ON dialplan        TO kamailio_readonly;
        GRANT SELECT ON uacreg          TO kamailio_readonly;
        GRANT SELECT ON version         TO kamailio_readonly;

        -- Kamailio needs read+write on location (registrar)
        GRANT SELECT, INSERT, UPDATE, DELETE ON location TO kamailio_readonly;
        GRANT USAGE, SELECT ON SEQUENCE location_id_seq  TO kamailio_readonly;

        RAISE NOTICE 'Kamailio readonly permissions granted.';
    ELSE
        RAISE NOTICE 'kamailio_readonly role does not exist yet — skipping grants.';
    END IF;
END
$$;