CREATE TABLE IF NOT EXISTS brand_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE,
    brand_name VARCHAR(200) NOT NULL,
    industry VARCHAR(200),
    brand_voice TEXT,
    target_audience TEXT,
    brand_values TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS product_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    brand_context_id UUID NOT NULL REFERENCES brand_context(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_product_profile_brand ON product_profile (brand_context_id);

CREATE TABLE IF NOT EXISTS product_reference_image (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    product_profile_id UUID NOT NULL REFERENCES product_profile(id) ON DELETE CASCADE,
    bucket VARCHAR(200) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_reference_image UNIQUE (bucket, object_key)
);

CREATE INDEX IF NOT EXISTS idx_product_reference_image_product ON product_reference_image (product_profile_id);

CREATE TABLE IF NOT EXISTS reference_image_analysis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    reference_image_id UUID NOT NULL UNIQUE REFERENCES product_reference_image(id) ON DELETE CASCADE,
    description TEXT,
    dominant_colors TEXT,
    style_notes TEXT,
    subject_matter TEXT,
    suggested_use_case TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
