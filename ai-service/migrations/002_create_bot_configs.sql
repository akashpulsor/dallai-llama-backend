CREATE TABLE IF NOT EXISTS bot_configs (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    llm_provider VARCHAR(64) NULL,
    llm_model VARCHAR(128) NULL,
    stt_provider VARCHAR(64) NULL,
    stt_model VARCHAR(128) NULL,
    tts_provider VARCHAR(64) NULL,
    tts_model VARCHAR(128) NULL,
    tts_voice VARCHAR(128) NULL,
    tts_gender VARCHAR(32) NULL,
    language VARCHAR(32) NULL,
    provider_configs_json JSONB NULL,
    provider_credentials_json JSONB NULL,
    ui_state_json JSONB NULL,
    metadata_json JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_bot_configs_tenant_id
    ON bot_configs (tenant_id);

CREATE INDEX IF NOT EXISTS ix_bot_configs_is_active
    ON bot_configs (is_active);

CREATE INDEX IF NOT EXISTS ix_bot_configs_llm_provider
    ON bot_configs (llm_provider);

CREATE INDEX IF NOT EXISTS ix_bot_configs_stt_provider
    ON bot_configs (stt_provider);

CREATE INDEX IF NOT EXISTS ix_bot_configs_tts_provider
    ON bot_configs (tts_provider);

CREATE INDEX IF NOT EXISTS ix_bot_configs_created_at
    ON bot_configs (created_at);

CREATE INDEX IF NOT EXISTS ix_bot_configs_updated_at
    ON bot_configs (updated_at);

CREATE INDEX IF NOT EXISTS ix_bot_configs_tenant_active
    ON bot_configs (tenant_id, is_active);

CREATE INDEX IF NOT EXISTS ix_bot_configs_tenant_updated
    ON bot_configs (tenant_id, updated_at);
