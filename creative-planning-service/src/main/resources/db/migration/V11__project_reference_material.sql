-- A product created inline while starting a standalone brief (as opposed to via the full brand/
-- campaign journey) is tagged with the requirement it came from -- same dual-origin shape as
-- locked_idea's session_id/project_requirement_id pair.
ALTER TABLE product_profile
    ADD COLUMN project_requirement_id UUID REFERENCES project_requirement(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_product_profile_requirement ON product_profile (project_requirement_id);

-- "What the client has in mind" -- mood/style reference images attached directly to a project
-- requirement, distinct from product_reference_image (images of the actual product).
CREATE TABLE IF NOT EXISTS project_reference_image (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_requirement_id UUID NOT NULL REFERENCES project_requirement(id) ON DELETE CASCADE,
    bucket VARCHAR(200) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_reference_image UNIQUE (bucket, object_key)
);

CREATE INDEX IF NOT EXISTS idx_project_reference_image_requirement ON project_reference_image (project_requirement_id);

-- Same typed shape as reference_image_analysis, kept as its own table (rather than repointed at
-- both image tables) so the FK stays a real constraint instead of an untyped/polymorphic id.
CREATE TABLE IF NOT EXISTS project_reference_image_analysis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    reference_image_id UUID NOT NULL UNIQUE REFERENCES project_reference_image(id) ON DELETE CASCADE,
    description TEXT,
    dominant_colors TEXT,
    style_notes TEXT,
    subject_matter TEXT,
    suggested_use_case TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
