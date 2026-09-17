-- Every cut a shot has ever had, numbered, with exactly one of them current.
--
-- A shot's clip is not one file, it is a sequence of them: what the model generated, the same
-- picture carrying the dubbed voice instead of an invented one, the same picture with no voice at
-- all, and whatever the creator cut by hand and brought back. Until now each of those REPLACED the
-- last one and the job row held a single pointer, so making a new cut destroyed the ability to
-- compare it with the old one -- and a cut that came out wrong took the shot's only video with it.
--
-- Numbered like prompt versions, and for the same reason: "version 3" is something a creator can
-- talk about, point at and go back to, where a UUID is not. version_number is per shot and starts
-- at 1 for the generated clip.
--
-- status is what makes accept-before-replace possible. A new cut lands as PREVIEW: it exists, it can
-- be watched, and it is not yet what the film uses. Accepting it promotes it to ACTIVE and demotes
-- whatever was ACTIVE to SUPERSEDED. Nothing is deleted at any point -- the objects are cheap and
-- the ability to go back is not.
CREATE TABLE shot_clip_version (
    version_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    project_id       UUID         NOT NULL,
    shot_id          UUID         NOT NULL,
    shot_ref         VARCHAR(64),
    -- The video-gen job this shot's clip came from, kept so a cut can always be traced back to the
    -- render that was paid for.
    source_job_id    UUID,
    version_number   INTEGER      NOT NULL,
    -- GENERATED, DUBBED, SILENT, UPLOADED. What was done to the picture to make this cut.
    origin           VARCHAR(32)  NOT NULL,
    -- PREVIEW, ACTIVE, SUPERSEDED. Exactly one ACTIVE per shot, enforced below.
    status           VARCHAR(16)  NOT NULL,
    -- Where the file is. Bucket and key, never a URL: a presigned URL expires within the hour, so
    -- a version stored as one is a record of something that used to be fetchable.
    bucket           VARCHAR(128) NOT NULL,
    object_key       VARCHAR(1024) NOT NULL,
    duration_seconds NUMERIC(10, 3),
    width            INTEGER,
    height           INTEGER,
    has_audio        BOOLEAN,
    created_by       UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at      TIMESTAMPTZ
);

-- One current cut per shot, as a rule the database keeps rather than one the service remembers to.
-- Two concurrent accepts would otherwise both promote and leave a shot with two ACTIVE versions,
-- and every read after that would pick whichever the query happened to order first.
CREATE UNIQUE INDEX uq_shot_clip_version_active
    ON shot_clip_version (shot_id) WHERE status = 'ACTIVE';

-- Version numbers are per shot and never reused, so the same pair cannot be written twice by two
-- requests racing to be version 4.
CREATE UNIQUE INDEX uq_shot_clip_version_number
    ON shot_clip_version (shot_id, version_number);

-- The read the page makes constantly: every cut of one shot, newest first.
CREATE INDEX idx_shot_clip_version_shot ON shot_clip_version (shot_id, version_number DESC);

-- The read the film assembly makes: the current cut of every shot in a project, in one query
-- instead of one per shot. Partial, because ACTIVE is the only status it ever asks for.
CREATE INDEX idx_shot_clip_version_project_active
    ON shot_clip_version (project_id) WHERE status = 'ACTIVE';

-- Tenant-scoped sweeps (audit, deletion) without a join.
CREATE INDEX idx_shot_clip_version_tenant ON shot_clip_version (tenant_id, created_at DESC);
