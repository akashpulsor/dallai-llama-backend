ALTER TABLE voice_profile ADD COLUMN reference_audio_bucket VARCHAR(255);
ALTER TABLE voice_profile ADD COLUMN reference_audio_object_key VARCHAR(1024);
UPDATE voice_profile SET reference_audio_bucket = 'unknown', reference_audio_object_key = 'unknown' WHERE reference_audio_bucket IS NULL;
ALTER TABLE voice_profile ALTER COLUMN reference_audio_bucket SET NOT NULL;
ALTER TABLE voice_profile ALTER COLUMN reference_audio_object_key SET NOT NULL;
