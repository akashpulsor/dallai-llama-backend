-- BCP-47 dialogue language captured at generate() time -- same "snapshot the shot's context now
-- so approve()'s later dispatch reads a stable value" pattern already used for emotion/builtin
-- voice id. Forwarded to the ElevenLabs TTS call as an explicit language_code hint so
-- eleven_multilingual_v2 doesn't misidentify the language from romanized Hindi/Hinglish text
-- alone -- the concrete bug where a Hinglish script was speaking with an English accent.
ALTER TABLE video_gen_job_dialogue_beat ADD COLUMN language_code VARCHAR(16);
