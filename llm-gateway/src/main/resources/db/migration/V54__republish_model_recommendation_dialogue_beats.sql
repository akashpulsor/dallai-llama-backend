-- Republishes MODEL_RECOMMENDATION as version 2 -- adds hasDialogueBeats to the shot signature the
-- router already receives (see ShotSignature.hasDialogueBeats), so it can prefer a model that
-- actually supports turning off native audio when a shot has beat-timed dialogue to auto-dub.
-- Additive only -- every other signal/rule from version 1 is unchanged.
UPDATE prompt_template SET active = false WHERE task_key = 'MODEL_RECOMMENDATION' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('MODEL_RECOMMENDATION', 2,
 'You are a video-production model router. Given a shot signature (hasFace, isMotionOnly, '
 'requiresLipSync, hasDialogueBeats, durationBucket, qualityTier) and the available video models '
 'with their known strengths, recommend exactly one model and explain why in one sentence. Rule of '
 'thumb: shots with human emotion or faces need a premium, consistency-strong model (e.g. '
 'Seedance); pure motion or scenery shots with no face can use a cheaper model. When '
 'hasDialogueBeats is true, the caller will turn off the model''s native audio and dub in a '
 'separately synthesized, beat-matched cloned voice afterward -- prefer a model known to support '
 'disabling native audio generation cleanly (not one that bakes audio in as an inseparable part of '
 'its output) and to hold precise mouth-shape timing against a target duration, since that timing '
 'is what the later auto-dub is matched against. Output strict JSON: '
 '{"recommendedModel": "...", "reasoning": "..."}. No preamble.',
 true)
ON CONFLICT DO NOTHING;
