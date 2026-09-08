-- The built-in ElevenLabs voices (seeded in V80) already speak en-US + hi-IN via
-- eleven_multilingual_v2's Hindi/English weights. Hinglish is served by the same weights -- the
-- difference is a language_code hint to the model, not a different voice -- so the same four
-- voices also cover hi-Latn-IN. Registering that here makes them show up in a Hinglish-project's
-- built-in voice picker; without this row the picker's language filter would return empty when a
-- creator picks Hinglish as their dialogue language.
INSERT INTO builtin_voice_language (voice_id, language_code)
SELECT voice_id, 'hi-Latn-IN'
FROM builtin_voice
WHERE voice_id IN ('elevenlabs-sarah', 'elevenlabs-alice', 'elevenlabs-george', 'elevenlabs-brian')
ON CONFLICT DO NOTHING;
