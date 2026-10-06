-- Sound layers are prepared on the worker side (SoundLayerRequestedConsumer), one at a time like
-- every other ffmpeg job here, so a row now exists before its audio does: QUEUED until the consumer
-- has generated (or picked up the upload), probed and stored it. A generated layer has no object
-- yet while queued, hence bucket/object_key become nullable. Existing rows were stored inline, so
-- they are already COMPLETED.
ALTER TABLE sound_layer
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN error  TEXT,
    -- The length asked for, for music, kept until the worker generates it.
    ADD COLUMN requested_seconds INTEGER,
    ALTER COLUMN bucket DROP NOT NULL,
    ALTER COLUMN object_key DROP NOT NULL;
CREATE INDEX idx_sound_layer_status ON sound_layer (status) WHERE status <> 'COMPLETED';
