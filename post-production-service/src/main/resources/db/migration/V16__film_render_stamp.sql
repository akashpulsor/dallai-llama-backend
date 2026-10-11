-- A published film also gets a copy stamped with the Dalaillama mark; every upload made from the
-- platform (the creator's own channel and Dalaillama's) uses that copy. Prepared off the request
-- thread by FilmStampConsumer, one at a time, picked up by FilmStampService's sweep.
ALTER TABLE film_render
    ADD COLUMN stamped_object_key  VARCHAR(1024),
    -- NULL = not asked yet; PENDING = queued; DONE; FAILED (retried a few times).
    ADD COLUMN stamp_status        VARCHAR(16),
    ADD COLUMN stamp_error         TEXT,
    ADD COLUMN stamp_attempts      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN stamp_requested_at  TIMESTAMPTZ;

CREATE INDEX idx_film_render_stamp_due ON film_render (published, stamp_status) WHERE stamped_object_key IS NULL;
