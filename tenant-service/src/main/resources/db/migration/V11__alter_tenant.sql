-- 1. Add infrastructure and config columns to tenant_apps
ALTER TABLE tenant_apps
    ADD COLUMN IF NOT EXISTS deployment_model VARCHAR(50),
    ADD COLUMN IF NOT EXISTS dashboard_url VARCHAR(255),

    -- Plan & Product
    ADD COLUMN IF NOT EXISTS plan_id UUID,
    ADD COLUMN IF NOT EXISTS plan_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS plan_assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS product_code VARCHAR(100),

    -- AI Features (Postgres Booleans)
    ADD COLUMN IF NOT EXISTS ai_transcription_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ai_routing_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ai_noise_cancellation_enabled BOOLEAN DEFAULT FALSE,

    -- Infrastructure
    ADD COLUMN IF NOT EXISTS kafka_bootstrap VARCHAR(255),
    ADD COLUMN IF NOT EXISTS redis_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS postgres_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mysql_url VARCHAR(255),

    -- SIP / Telephony
    ADD COLUMN IF NOT EXISTS sip_external_ip VARCHAR(50),
    ADD COLUMN IF NOT EXISTS sip_udp_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS sip_tls_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS turn_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS websocket_url VARCHAR(255),
    ADD COLUMN IF NOT EXISTS rtpengine_sock VARCHAR(255),

    -- FreeSWITCH
    ADD COLUMN IF NOT EXISTS freeswitch_esl_host VARCHAR(255),
    ADD COLUMN IF NOT EXISTS freeswitch_esl_port INTEGER,
    ADD COLUMN IF NOT EXISTS freeswitch_esl_password VARCHAR(255),

    -- DIDWW
    ADD COLUMN IF NOT EXISTS didww_trunk_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS didww_sip_config_id VARCHAR(100),

    -- CC Builder Config (Using JSONB for Postgres performance)
    ADD COLUMN IF NOT EXISTS cc_builder_config JSONB,
    -- CC Builder Config (Using JSONB for Postgres performance)
    ADD COLUMN IF NOT EXISTS namespace VARCHAR(100);


ALTER TABLE tenants
    DROP COLUMN IF EXISTS deployment_model,
    DROP COLUMN IF EXISTS dashboard_url,
    DROP COLUMN IF EXISTS plan_id,
    DROP COLUMN IF EXISTS plan_code,
    DROP COLUMN IF EXISTS plan_assigned_at,
    DROP COLUMN IF EXISTS product_code,
    DROP COLUMN IF EXISTS ai_transcription_enabled,
    DROP COLUMN IF EXISTS ai_routing_enabled,
    DROP COLUMN IF EXISTS ai_noise_cancellation_enabled,
    DROP COLUMN IF EXISTS kafka_bootstrap,
    DROP COLUMN IF EXISTS redis_url,
    DROP COLUMN IF EXISTS postgres_url,
    DROP COLUMN IF EXISTS mysql_url,
    DROP COLUMN IF EXISTS sip_external_ip,
    DROP COLUMN IF EXISTS sip_udp_url,
    DROP COLUMN IF EXISTS sip_tls_url,
    DROP COLUMN IF EXISTS turn_url,
    DROP COLUMN IF EXISTS websocket_url,
    DROP COLUMN IF EXISTS rtpengine_sock,
    DROP COLUMN IF EXISTS freeswitch_esl_host,
    DROP COLUMN IF EXISTS freeswitch_esl_port,
    DROP COLUMN IF EXISTS freeswitch_esl_password,
    DROP COLUMN IF EXISTS didww_trunk_id,
    DROP COLUMN IF EXISTS didww_sip_config_id,
    DROP COLUMN IF EXISTS cc_builder_config,

    DROP COLUMN IF EXISTS namespace;