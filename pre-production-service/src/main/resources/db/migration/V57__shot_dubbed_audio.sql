-- Persisted dubbed-audio URL per shot -- the output of the video-page "Dub all shots" button
-- (video-generation-service /v1/voice-tests/dub-shot), which runs the same clone-if-needed +
-- TTS path BeatDubbingService uses at approve() time but writes the resulting audio bytes to
-- MinIO and hands the object handle back here to persist. Frontend reads it via ShotView and
-- shows a per-shot audio player so the creator can preview the dub without re-firing it.
--
-- Bucket+objectKey (not a signed URL) so the URL can be resigned per read -- signed URLs are
-- short-lived (typically 1 hour); a persistent signed URL would break as soon as it expired.
ALTER TABLE shot ADD COLUMN dubbed_audio_bucket VARCHAR(64);
ALTER TABLE shot ADD COLUMN dubbed_audio_object_key VARCHAR(512);
