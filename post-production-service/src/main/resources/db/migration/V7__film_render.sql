-- The whole film: every shot's current cut, joined, at one size.
--
-- A job row rather than a bare output, for the same reason every other long job here has one. The
-- join runs ffmpeg over the entire project and takes minutes, so the request that asks for it
-- cannot also be the one that waits for it -- and a page polling for the answer needs something to
-- poll. The row is written before the work starts, so a crash leaves evidence rather than silence.
--
-- published is the gate on the client's review page. The page shows no film at all until it is
-- set: not "no download", invisible. That is deliberate -- a cut the creator has not chosen to show
-- should not be reachable by anyone holding the review link.
CREATE TABLE film_render (
    render_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    project_id       UUID         NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    shot_count       INTEGER,
    -- The clip versions this cut of the film was built from, so a published film can always be
    -- traced back to exactly which cut of each shot it contains.
    source_version_ids TEXT,
    width            INTEGER,
    height           INTEGER,
    duration_seconds NUMERIC(10, 3),
    bucket           VARCHAR(128),
    object_key       VARCHAR(1024),
    published        BOOLEAN      NOT NULL DEFAULT false,
    published_at     TIMESTAMPTZ,
    last_error       TEXT,
    created_by       UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

-- The read the page makes: the newest film for this project.
CREATE INDEX idx_film_render_project ON film_render (project_id, created_at DESC);

-- The read the client's review page makes, by project, wanting only what was published.
CREATE INDEX idx_film_render_published ON film_render (project_id, created_at DESC) WHERE published;

CREATE INDEX idx_film_render_tenant ON film_render (tenant_id, created_at DESC);
