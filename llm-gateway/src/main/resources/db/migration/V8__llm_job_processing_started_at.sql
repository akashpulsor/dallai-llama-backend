-- Crash-recovery support: distinct from created_at (first-ever creation) so a retry's
-- staleness clock is measured from when the CURRENT attempt started, not the original one.
ALTER TABLE llm_job ADD COLUMN processing_started_at TIMESTAMPTZ;

UPDATE llm_job SET processing_started_at = created_at WHERE processing_started_at IS NULL;
