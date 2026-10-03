-- When the client approved and locked the package on the review page.
--
-- "Locked" used to be nothing but Project.status = CLIENT_LOCKED, and status is the one column
-- every stage keeps rewriting: re-running a stage moves it backward, and from READY_FOR_REVIEW
-- the state machine had no edge to CLIENT_LOCKED at all, so the lock threw AFTER the client's
-- payment had been captured and the review page offered "Approve & lock" again on refresh. A paid
-- lock is a fact about the client, not a production stage, so it gets its own column that nothing
-- but the lock ever writes.
--
-- NULL = never locked. Projects currently sitting at CLIENT_LOCKED are backfilled from
-- updated_at, the closest record of when that happened.
ALTER TABLE project ADD COLUMN client_locked_at TIMESTAMPTZ;

UPDATE project SET client_locked_at = updated_at WHERE status = 'CLIENT_LOCKED';

COMMENT ON COLUMN project.client_locked_at IS
    'When the client approved and locked the package. NULL = not locked. Never cleared by stage changes.';
