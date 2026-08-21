ALTER TABLE project_requirement
    ADD COLUMN share_token_expires_at TIMESTAMPTZ;

-- Backfill any pre-existing rows (there shouldn't be real ones yet, but keep NOT NULL honest)
-- before enforcing the constraint.
UPDATE project_requirement SET share_token_expires_at = created_at + INTERVAL '7 days' WHERE share_token_expires_at IS NULL;

ALTER TABLE project_requirement
    ALTER COLUMN share_token_expires_at SET NOT NULL;
