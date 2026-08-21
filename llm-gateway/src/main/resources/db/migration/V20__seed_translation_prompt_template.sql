-- The standalone dubbing flow's translation step reuses the existing text-chat path
-- (gemini-2.5-flash via GoogleGeminiProvider) -- translation is a plain text task, it doesn't
-- need a new provider or model, just a prompt template, same registry every other task-specific
-- prompt (PHONEME_GUIDE, PROMPT_COMPRESSION, ...) already goes through.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('TRANSLATE_DIALOGUE', 1,
 'Translate the following dialogue transcript from {{sourceLanguage}} to {{targetLanguage}}. '
 'Preserve line breaks and speaker structure exactly as given. Preserve tone and register '
 '(casual stays casual, formal stays formal). Do not add commentary, notes, or explanations. '
 'Output ONLY the translated transcript, nothing else.',
 true);
