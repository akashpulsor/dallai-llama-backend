-- Stage-1 output of the prepare-scene flow: one row per project, holding the composed
-- project-scope prompt template (the constant material every shot in the project inherits --
-- brand/product identity, continuity bible, dialogue-language defaults, editing-plan overview).
-- Idempotent overwrite; per-shot prompt versioning stays on shot_prompt.parent_prompt_id.
CREATE TABLE project_scene_preparation (
    project_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    template_text TEXT NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_project_scene_preparation_tenant ON project_scene_preparation (tenant_id);
