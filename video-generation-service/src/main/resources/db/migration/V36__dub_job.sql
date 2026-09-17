-- A dub that can be watched while it runs.
--
-- Dubbing a shot clones or selects a voice and synthesizes the line, which takes seconds to tens of
-- seconds -- long enough that the request holding the connection is the wrong shape, and long enough
-- that a creator pressing the button twice is a reasonable thing to do when nothing on screen says
-- it is working. There was nothing to show them because there was nothing to ask: the work existed
-- only as an in-flight HTTP call.
--
-- This is that something. The row is written before the work starts, so a page has a job id to poll
-- from the first moment, and a pod that dies mid-dub leaves evidence rather than silence.
CREATE TABLE dub_job (
    job_id        UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL,
    project_id    UUID         NOT NULL,
    shot_id       UUID         NOT NULL,
    -- The words being spoken. Kept on the job so a finished dub can be matched against the line it
    -- was made from -- which is how a take recorded from since-replaced words is spotted.
    text          TEXT,
    -- QUEUED, PROCESSING, COMPLETED, FAILED.
    status        VARCHAR(32)  NOT NULL,
    audio_url     TEXT,
    duration_ms   INTEGER,
    last_error    TEXT,
    created_by    UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- The page's read: the newest dub for this shot.
CREATE INDEX idx_dub_job_shot ON dub_job (shot_id, created_at DESC);

-- The sweep for jobs that were queued and never finished.
CREATE INDEX idx_dub_job_status ON dub_job (status, created_at);

CREATE INDEX idx_dub_job_tenant ON dub_job (tenant_id, created_at DESC);
