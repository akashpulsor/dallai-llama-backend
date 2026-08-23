-- Screenplay was one-row-per-project (project_id UNIQUE), so every regenerate destructively
-- overwrote the previous draft with no way back and no record of what changed. This makes it a
-- real version history instead: drop the uniqueness, add a version number, and track whether a
-- version came from an LLM generation or a creator's manual edit of an earlier one (same
-- GENERATED/EDITED + parent_id lineage pattern creative-planning-service's idea_option table
-- uses). ScreenplayGenerationService.generate() now always inserts a new row rather than finding
-- and reusing the existing one.
ALTER TABLE screenplay DROP CONSTRAINT screenplay_project_id_key;
ALTER TABLE screenplay ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE screenplay ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'GENERATED';
ALTER TABLE screenplay ADD COLUMN parent_id UUID REFERENCES screenplay(id);

CREATE INDEX idx_screenplay_project_version ON screenplay(project_id, version DESC);
