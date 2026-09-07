-- Adds Hinglish as a first-class language (Roman-script Hindi with English loanwords -- the
-- dominant conversational register for Indian urban audiences that neither pure Hindi nor pure
-- English quite hits). The BCP-47 code hi-Latn-IN follows the standard for a
-- Hindi-in-Latin-script-in-India variant. Existing frontend components (IdeaCandidatesPanel,
-- ClientReviewPanel, ScreenplayVideoGenerationPanel) already reference "Hinglish" as a language
-- name, so this backfills the master data row that never existed to back them.
INSERT INTO language_master (language_code, display_name, native_name) VALUES
    ('hi-Latn-IN', 'Hinglish', 'Hinglish')
ON CONFLICT (language_code) DO NOTHING;

-- Same TTS/voice-clone support as the other listed languages -- the models handle Hinglish text
-- via their Hindi weights; this is a labeling/entry issue, not a model-capability one.
INSERT INTO model_supported_language (model_id, language_code) VALUES
    ('voice-clone-v1', 'hi-Latn-IN'),
    ('tts-v1', 'hi-Latn-IN')
ON CONFLICT DO NOTHING;
