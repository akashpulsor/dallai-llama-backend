-- Add missing AI feature columns to plan_entitlements
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_tts_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_transcription_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Enable TTS and transcription for plans that already have STT enabled
UPDATE plan_entitlements SET ai_tts_enabled = TRUE WHERE ai_stt_enabled = TRUE;
UPDATE plan_entitlements SET ai_transcription_enabled = TRUE WHERE ai_stt_enabled = TRUE;
