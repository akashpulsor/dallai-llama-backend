-- Pro-plan: creator can attach a face image to a stock/built-in AI voice so a voice-cloned
-- character has a matching visual identity for the shot page's cast picker. Face is uploaded
-- via POST /v1/voices/{voiceId}/face on llm-gateway and stored in MinIO alongside the audio
-- refs.
--
-- Nullable: not every voice has a face. Existing voices stay unchanged; the frontend degrades
-- to no thumbnail when face_ref_object_key is null.
ALTER TABLE builtin_voice
    ADD COLUMN IF NOT EXISTS face_ref_bucket       VARCHAR(120),
    ADD COLUMN IF NOT EXISTS face_ref_object_key   VARCHAR(512),
    ADD COLUMN IF NOT EXISTS face_ref_content_type VARCHAR(120);
