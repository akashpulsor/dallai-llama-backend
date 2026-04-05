CREATE TABLE IF NOT EXISTS provider_catalog_entries (
    id VARCHAR(36) PRIMARY KEY,
    modality VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    label VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    config_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_provider_catalog_modality
    ON provider_catalog_entries (modality);

CREATE INDEX IF NOT EXISTS ix_provider_catalog_provider
    ON provider_catalog_entries (provider);

CREATE INDEX IF NOT EXISTS ix_provider_catalog_is_active
    ON provider_catalog_entries (is_active);

CREATE INDEX IF NOT EXISTS ix_provider_catalog_modality_provider
    ON provider_catalog_entries (modality, provider);

CREATE INDEX IF NOT EXISTS ix_provider_catalog_modality_active_sort
    ON provider_catalog_entries (modality, is_active, sort_order);
