-- V2__insert_initial_data.sql

-- Define a UUID for the dummy tenant to ensure consistency
SET @tenant_uuid = UUID();

-- 1. Insert Dummy Tenant
INSERT INTO tenants (
    id, name, realm, deployment_model, region, status,
    ai_routing_enabled, ai_transcription_enabled, ai_noise_cancellation_enabled
) VALUES (
    @tenant_uuid,
    'Dalai Llama Default Tenant',
    'default.pbx',
    'shared',
    'us-east-1',
    'active',
    1, 1, 1
);

-- 2. Insert Dummy Signaling Configuration for the Tenant
INSERT INTO signaling_configs (
    id, tenant_id, auth_realm, turn_realm, turn_policy, turn_port_range,
    wss_url, sip_fqdn, pbx_fqdn, turn_fqdn, wss_fqdn,
    enable_rtp_engine, enable_turn, sbc_enabled, version
) VALUES (
    UUID(),
    @tenant_uuid,
    'default.pbx',
    'turn.default.pbx',
    'relay',
    '49152-65535',
    'wss://pbx.default.pbx:8443/ws',
    'sip.default.pbx',
    'pbx.default.pbx',
    'turn.default.pbx',
    'wss.default.pbx',
    1, 1, 1, 1
);

-- 3. Insert a Dummy Trunk
INSERT INTO trunks (
    id, tenant_id, name, sip_uri, username, password, prefixes, priority, weight, enabled
) VALUES (
    UUID(),
    @tenant_uuid,
    'Default Outbound Trunk',
    'sip:trunk.example.com:5060',
    'trunk_user',
    'trunk_pass',
    '+1,+44',
    1,
    10,
    1
);

-- 4. Insert a Dummy DID (Direct Inward Dialing)
INSERT INTO dids (
    id, tenant_id, number, entrypoint, trunk_id, status
) VALUES (
    UUID(),
    @tenant_uuid,
    '+18005551234',
    'team:support',
    (SELECT id FROM trunks WHERE tenant_id = @tenant_uuid LIMIT 1),
    'active'
);