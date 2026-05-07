-- Adds country and priority columns to sip_trunks for country-aware platform trunk
-- selection. Required by SipTrunkRepository.findBestPlatformTrunkForCountry().
--
-- country: ISO 3166-1 alpha-2 country code. Required for platform trunks
--          (tenant_id IS NULL); routing breaks if NULL on a platform row.
-- priority: lower value = higher priority. Default 100. Used to break ties
--           when multiple platform trunks match a country.

ALTER TABLE sip_trunks
    ADD COLUMN country VARCHAR(2),
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 100;

CREATE INDEX idx_sip_trunks_platform_country
    ON sip_trunks (country, status, priority, is_healthy)
    WHERE tenant_id IS NULL;

INSERT INTO sip_trunks (
    id, tenant_id, name, provider, server, port, transport,
    auth_type, codecs, max_concurrent_calls, max_calls_per_second,
    status, is_healthy, country, priority,
    created_at, updated_at, version
) VALUES (
    gen_random_uuid(), NULL, 'Epsilon Platform Trunk - India', 'EPSILON',
    'sip.epsilon.in', 5060, 'UDP',
    'IP', '["PCMU","PCMA"]'::jsonb,
    100, 50,
    'ACTIVE', true, 'IN', 100,
    NOW(), NOW(), 0
);