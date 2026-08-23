CREATE TABLE shot_product_reference (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shot_id UUID NOT NULL UNIQUE REFERENCES shot(id),
    classification VARCHAR(16) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    person_description TEXT,
    detected_subject TEXT,
    dominant_mood VARCHAR(160),
    reference_camera_angle VARCHAR(160),
    reference_lighting_style VARCHAR(160),
    reference_motion VARCHAR(160),
    ignore_subject BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
