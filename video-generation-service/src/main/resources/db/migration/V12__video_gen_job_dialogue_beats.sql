-- Persisted at generate() time (from the incoming ShotContext.dialogueBeats), read back at
-- approve() time -- same "capture at creation, use at dispatch" shape V11 already established for
-- duration_seconds/aspect_ratio. mute_audio is the derived decision (every beat resolved a voice
-- reference) of whether this job's Seedance call should turn off native audio and expect a
-- post-dispatch auto-dub mux instead.
ALTER TABLE video_gen_job ADD COLUMN mute_audio BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE video_gen_job_dialogue_beat (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES video_gen_job(job_id) ON DELETE CASCADE,
    order_index INTEGER NOT NULL,
    start_seconds NUMERIC(6,2) NOT NULL,
    duration_seconds NUMERIC(6,2) NOT NULL,
    text TEXT NOT NULL,
    character_key VARCHAR(160),
    voice_reference_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_video_gen_job_dialogue_beat_job ON video_gen_job_dialogue_beat(job_id, order_index);
