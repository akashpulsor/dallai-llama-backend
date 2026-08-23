-- LockedIdea previously only ever came from a campaign-planning chat session (session_id
-- NOT NULL, UNIQUE). The standalone brief path (ProjectRequirement with no session at all)
-- needs its own way to produce one, so it can still hand off to pre-production-service via
-- the existing from-locked-idea endpoint. Two entry points, same handoff artifact -- same
-- pattern ProjectRequirement itself already uses for locked_idea_id.
ALTER TABLE locked_idea ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE locked_idea ADD COLUMN project_requirement_id UUID;

ALTER TABLE locked_idea ADD CONSTRAINT chk_locked_idea_origin
    CHECK (
        (session_id IS NOT NULL AND project_requirement_id IS NULL)
        OR (session_id IS NULL AND project_requirement_id IS NOT NULL)
    );

CREATE UNIQUE INDEX idx_locked_idea_project_requirement ON locked_idea(project_requirement_id)
    WHERE project_requirement_id IS NOT NULL;
