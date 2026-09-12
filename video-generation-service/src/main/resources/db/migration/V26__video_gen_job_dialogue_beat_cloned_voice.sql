-- Snapshot the reusable provider voice prepared for an actor. The job can be approved after the
-- pre-production profile changes, so dialogue rendering must use the identity chosen at generate time.
ALTER TABLE video_gen_job_dialogue_beat ADD COLUMN cloned_voice_id VARCHAR(128);
ALTER TABLE video_gen_job_dialogue_beat ADD COLUMN cloned_voice_provider_id VARCHAR(64);