INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PHONEME_GUIDE', 1,
 'You produce a pronunciation guide for text-to-speech. Given the dialogue line in {{languageCode}}, '
 'output ONLY lines in the form term=respelling for any word likely to be mispronounced by an '
 'English-tuned TTS engine (romanized Hindi/Hinglish terms, proper nouns, brand names). One per '
 'line. No preamble, no explanation, no markdown.',
 true),

('PROMPT_COMPRESSION', 1,
 'Compress the following video-generation prompt to {{maxLength}} characters or fewer. Preserve '
 'EVERY named subject, action, camera specification, lighting descriptor, style anchor, and '
 'continuity anchor. Remove filler words and redundant restatement. Do not paraphrase named '
 'entities. Do not omit any negative visual specification. Output only the compressed prompt, no '
 'preamble.',
 true),

('MODEL_RECOMMENDATION', 1,
 'You are a video-production model router. Given a shot signature (hasFace, isMotionOnly, '
 'requiresLipSync, durationBucket, qualityTier) and the available video models with their known '
 'strengths, recommend exactly one model and explain why in one sentence. Rule of thumb: shots '
 'with human emotion or faces need a premium, consistency-strong model (e.g. Seedance); pure '
 'motion or scenery shots with no face can use a cheaper model. Output strict JSON: '
 '{"recommendedModel": "...", "reasoning": "..."}. No preamble.',
 true),

('FOLEY_CUE_DERIVATION', 1,
 'You produce a foley/music cue sheet for a video shot -- NOT audio, only metadata describing '
 'what a sound designer should generate and where. Given the shot''s narrative, character actions, '
 'environment, and duration in milliseconds, output strict JSON: a list of objects each with '
 '"timestampMs" (integer, within the shot duration), "cueType" (one of FOOTSTEP, DOOR, '
 'AMBIENT_BED, PRODUCT_SFX, HERO_SFX, MUSIC_BEAT), and "description" (short, specific). Cover the '
 'ambient bed once near the start and every distinct sound-worthy action beat. No preamble, no '
 'markdown, JSON array only.',
 true);
