-- V{next}__add_telecom_and_cc_builder_columns.sql

-- FreeSWITCH ESL columns
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS freeswitch_esl_host VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS freeswitch_esl_port INTEGER;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS freeswitch_esl_password VARCHAR(255);

-- AI feature flags
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_transcription_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_routing_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_noise_cancellation_enabled BOOLEAN DEFAULT FALSE;

-- Billing email
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS billing_email VARCHAR(255);

-- CC Builder Config (stores wizard output as JSON)
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS cc_builder_config JSONB;

-- Add index for faster JSON queries if needed
CREATE INDEX IF NOT EXISTS idx_tenants_cc_builder_config ON tenants USING GIN (cc_builder_config);