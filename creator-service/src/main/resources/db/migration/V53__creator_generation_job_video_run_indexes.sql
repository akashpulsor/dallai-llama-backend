CREATE INDEX IF NOT EXISTS idx_creator_jobs_input_run_id
    ON creator_generation_jobs (tenant_id, user_id, ((input_payload ->> 'runId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_output_run_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload ->> 'runId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_video_run_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload -> 'videoRun' ->> 'runId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_screenplay_video_run_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload -> 'screenplayVideoRun' ->> 'runId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_input_script_id
    ON creator_generation_jobs (tenant_id, user_id, ((input_payload ->> 'scriptId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_output_script_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload ->> 'scriptId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_video_run_script_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload -> 'videoRun' ->> 'scriptId')));

CREATE INDEX IF NOT EXISTS idx_creator_jobs_screenplay_video_run_script_id
    ON creator_generation_jobs (tenant_id, user_id, ((output_payload -> 'screenplayVideoRun' ->> 'scriptId')));
