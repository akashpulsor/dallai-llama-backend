-- Captured from ShotContext.technical().voiceCloneModel() at generate() time, same
-- capture-then-read-at-approve() pattern already used for duration_seconds/aspect_ratio.
ALTER TABLE video_gen_job ADD COLUMN voice_clone_model VARCHAR(160);
