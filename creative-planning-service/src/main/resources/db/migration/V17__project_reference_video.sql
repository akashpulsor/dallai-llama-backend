-- Client-uploaded reference videos on the brief, distinct from product/reference images. Stored
-- as an ad-hoc requirement input -- the client can attach one or more short clips (typical case
-- is a zip of existing product/lifestyle footage the client wants shots reused from). No auto-
-- analysis at brief time (client has not paid yet); the LLM script prompt only gets the user's
-- own text description of what to convey, and the actual "pull shot X from clip Y" decision is
-- a manual step later in the pipeline. Mirrors project_reference_image structure so upload,
-- listing, and delete flows can reuse the same MinIO helpers.
CREATE TABLE IF NOT EXISTS project_reference_video (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_requirement_id UUID NOT NULL REFERENCES project_requirement(id) ON DELETE CASCADE,
    bucket VARCHAR(200) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(400),
    content_type VARCHAR(100),
    size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_reference_video UNIQUE (bucket, object_key)
);

CREATE INDEX IF NOT EXISTS idx_project_reference_video_requirement ON project_reference_video (project_requirement_id);

-- Ad-hoc shot-intent capture: "do you want us to pull specific shots from those videos, and if
-- so, what should they convey?" Two nullable fields on project_requirement rather than a
-- separate table -- one requirement has at most one intent statement, and the LLM only needs
-- the free-text version of "what to convey" to leave room for a manual shot later.
ALTER TABLE project_requirement
    ADD COLUMN IF NOT EXISTS include_video_shots BOOLEAN,
    ADD COLUMN IF NOT EXISTS video_shots_intent TEXT;
