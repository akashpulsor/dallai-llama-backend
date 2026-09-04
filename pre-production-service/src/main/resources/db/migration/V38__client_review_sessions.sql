-- Per-project review allowance (how many client review rounds are included before the paywall).
-- Configurable per project from the new-project tab; optional there with a default of 2.
ALTER TABLE project ADD COLUMN review_allowance INT NOT NULL DEFAULT 2;

-- A client review is TRANSACTIONAL: the client opens a review, chats as many messages as they
-- want inside it, then closes it. One open review at a time; the number of STARTED reviews (not
-- chat messages) is what counts against the allowance. `paid` marks a review that was unlocked by
-- an extra-review payment (i.e. started beyond the free allowance).
CREATE TABLE client_review_session (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    project_id   UUID NOT NULL REFERENCES project(id),
    -- OPEN while the client is actively reviewing; CLOSED once they end it.
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    -- SATISFIED (apply to storyboard) or CHANGES_REQUESTED, set when the review is closed.
    outcome      VARCHAR(24),
    paid         BOOLEAN NOT NULL DEFAULT FALSE,
    started_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ
);

CREATE INDEX idx_client_review_session_project ON client_review_session(project_id);
-- At most one OPEN review per project.
CREATE UNIQUE INDEX uq_client_review_session_open ON client_review_session(project_id) WHERE status = 'OPEN';
