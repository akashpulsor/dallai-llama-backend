ALTER TABLE creator_shot_takes
    ADD COLUMN IF NOT EXISTS media_analysis JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN creator_shot_takes.media_analysis IS
    'Client or worker generated timeline analysis for uploaded takes: sampled frame thumbnails, audio waveform peaks, duration, and video metadata.';
