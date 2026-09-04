-- ShotContext.technical() carries duration_seconds/aspect_ratio, but generate() never persisted
-- them anywhere the later approve() call could read back -- so the actual fal.ai dispatch always
-- sent (null, null) for both, regardless of what the shot actually specified. Persisting them on
-- the job itself (set once at generate() time, read at approve() time) is the natural fix -- same
-- "capture at creation, read back at dispatch" shape prompt_original/negative_prompt already use.
ALTER TABLE video_gen_job ADD COLUMN duration_seconds INTEGER;
ALTER TABLE video_gen_job ADD COLUMN aspect_ratio VARCHAR(16);
