-- Every version a shot's clip has ever had, so replacing one is reversible.
--
-- A repair -- replacing the audio with the dubbed take, stripping the voice, extending the tail,
-- uploading a finished file -- writes a new object and repoints the job at it. The job holds one
-- pointer, so the clip it replaced became unreachable the moment the pointer moved. That is how a
-- finished shot came back with no video at all: a repair produced something unplayable, took the
-- shot's only pointer with it, and there was nothing to go back to.
--
-- The objects themselves were never deleted -- every repair writes a NEW key rather than
-- overwriting -- so nothing was ever really lost. What was missing was a record of where they were.
-- This is that record: one row per version, written BEFORE the pointer moves.
--
-- Deliberately keeps every version rather than only the previous one. The point is being able to
-- go back to the clip as generated after three repairs, not just to undo the last one, and a creator
-- comparing a silenced cut against a dubbed one needs both to still exist. That costs storage --
-- a few MB per version per shot -- which is the right trade against re-billing a generation.
CREATE TABLE video_gen_job_output_version (
    version_id       UUID PRIMARY KEY,
    job_id           UUID        NOT NULL,
    tenant_id        UUID        NOT NULL,
    -- Where the clip actually is. Nothing here is derived or guessed: a key written down when the
    -- version was current is the only thing that stays true after the naming scheme changes.
    bucket           VARCHAR(128) NOT NULL,
    object_key       VARCHAR(512) NOT NULL,
    duration_seconds INTEGER,
    -- What made this version: GENERATED, DUBBED, SILENCED, TAIL_FROZEN, TAIL_GENERATED, UPLOADED.
    -- Same vocabulary as video_gen_job.output_origin, and the same width since a value moves from
    -- one column to the other unchanged.
    origin           VARCHAR(32)  NOT NULL,
    -- When this version stopped being the shot's clip, which is when the row was written.
    superseded_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       UUID
);

-- The only access pattern: every version of one shot's clip, newest first.
CREATE INDEX idx_output_version_job ON video_gen_job_output_version (job_id, superseded_at DESC);

-- Tenant-scoped reads go through job_id, but the column is here so a tenant's rows can be found
-- without a join when one has to be deleted or audited.
CREATE INDEX idx_output_version_tenant ON video_gen_job_output_version (tenant_id);
