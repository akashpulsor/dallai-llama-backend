CREATE TABLE shot_image (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL REFERENCES shot(id),
    kind VARCHAR(16) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    prompt TEXT,
    reference_cast_profile_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_shot_image_shot_kind UNIQUE (shot_id, kind)
);
CREATE INDEX idx_shot_image_shot_id ON shot_image(shot_id);

-- Superseded by shot_image (kind=STORYBOARD) -- that table can hold all 4 image kinds, these
-- single columns could only ever hold one.
ALTER TABLE shot DROP COLUMN storyboard_image_bucket;
ALTER TABLE shot DROP COLUMN storyboard_image_object_key;
