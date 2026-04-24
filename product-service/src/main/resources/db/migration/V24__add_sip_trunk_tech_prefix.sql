-- Adds tech_prefix column for carriers that require an account-identifying
-- prefix on outbound INVITEs (e.g., Greeter requires '45454' prefix).
-- NULL for carriers that don't require a prefix.

ALTER TABLE sip_trunks
    ADD COLUMN tech_prefix VARCHAR(20);

COMMENT ON COLUMN sip_trunks.tech_prefix IS
    'Carrier tech prefix prepended to dialed numbers for outbound routing. NULL if not required.';


-- Assumes Greeter credentials will be filled in tomorrow
-- Values below are placeholders you can update once Greeter activates

INSERT INTO sip_trunks (
    id,
    tenant_id,
    name,
    provider,
    server,
    port,
    transport,
    auth_type,
    auth_username,
    auth_password_encrypted,
    allowed_ips,
    codecs,
    max_concurrent_calls,
    max_calls_per_second,
    status,
    last_health_check,
    is_healthy,
    didww_trunk_id,
    didww_sip_config_id,
    tech_prefix,
    created_at,
    updated_at,
    version
) VALUES (
    gen_random_uuid(),
    NULL,
    'Platform Trunk - Greeter (placeholder)',
    'CUSTOM',
    '103.20.104.213',
    3300,
    'UDP',
    'IP',
    'dalaillama-prod',
    'TO_BE_UPDATED_AFTER_GREETER_ACTIVATION',
    '103.20.104.213',
    '["PCMA","PCMU"]'::jsonb,
    100,
    10,
    'ACTIVE',
    now(),
    true,
    NULL,
    NULL,
    '45454',
    now(),
    now(),
    0
);

-- Verify
SELECT name, provider, server, port, transport, tech_prefix, allowed_ips, status
FROM sip_trunks
WHERE tenant_id IS NULL;