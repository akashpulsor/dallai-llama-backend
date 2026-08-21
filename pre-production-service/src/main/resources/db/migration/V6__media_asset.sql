CREATE TABLE IF NOT EXISTS media_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    bucket VARCHAR(200) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    asset_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_asset UNIQUE (bucket, object_key)
);
