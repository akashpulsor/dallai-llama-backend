-- IdeaOption was only ever generated pre-project-creation, scoped to a ProjectRequirement. This
-- lets the same generate/list/pick flow run against an already-created pre-production Project too
-- (a creator switching to a different idea after the project exists) -- same two-entry-point
-- pattern locked_idea already uses for session_id/project_requirement_id (see
-- V6__locked_idea_from_project_requirement.sql), mirrored here.
ALTER TABLE idea_option ALTER COLUMN project_requirement_id DROP NOT NULL;
ALTER TABLE idea_option ADD COLUMN project_id UUID;

ALTER TABLE idea_option ADD CONSTRAINT chk_idea_option_origin
    CHECK (
        (project_requirement_id IS NOT NULL AND project_id IS NULL)
        OR (project_requirement_id IS NULL AND project_id IS NOT NULL)
    );

-- Unlike locked_idea's one-per-requirement index, multiple IdeaOption rows are expected per
-- project over time (generate more, switch, generate more again) -- no uniqueness here, just a
-- lookup index matching idea_option's existing per-requirement one.
CREATE INDEX idx_idea_option_project ON idea_option(project_id, created_at DESC);
