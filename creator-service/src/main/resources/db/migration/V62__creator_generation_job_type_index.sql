-- creator_generation_jobs has no index covering job_type at all, despite it being
-- filtered in nearly every CreatorGenerationJobRepository query (findLatestScreenplayVideoRunJob,
-- findLatestScreenplayVideoRunJobForScript, findLatestScreenplayVideoAudioRunJob,
-- findActiveByIdempotencyKey, findLatestCompletedByIdempotencyKey). tenant_id/user_id +
-- a jsonb-expression index already narrow the SCREENPLAY_VIDEO run lookups tightly, but
-- job_type/status filtering is otherwise a sequential filter with no index backing.
CREATE INDEX IF NOT EXISTS idx_creator_jobs_tenant_user_type_status
    ON creator_generation_jobs (tenant_id, user_id, job_type, status);
