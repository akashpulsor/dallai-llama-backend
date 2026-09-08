-- Republishes PRE_PROD_IMAGE_DESCRIBE as version 2 -- adds an onScreenTextLanguage field so the
-- frontend can drive per-image text affordances (Download, "fix on-image text") not just by
-- "does this frame have text" but also by "what language is the text in", the same way the
-- built-in voice picker filters by language. Version 1 already asked for onScreenText but the
-- service was discarding it on read; the accompanying pre-production-service migration
-- (V56__shot_image_on_screen_text.sql) persists both fields.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_IMAGE_DESCRIBE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_IMAGE_DESCRIBE', 2, $$Describe the attached image in specific, concrete detail for someone who cannot see it but needs to discuss and give feedback on it. Cover: who/what is in frame (people, products, objects), their appearance (clothing, colors, expression, pose), the setting/background, lighting and mood, camera framing, and any visible on-screen text. Be factual and specific -- name actual colors, actual objects, actual text -- not vague generalities.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "description": "a full paragraph covering everything above",
  "onScreenText": "any literal text visible in the image, exactly as it appears (preserve capitalization, punctuation, and script), or null if there is no visible text",
  "onScreenTextLanguage": "BCP-47 language subtag or subtag+script of the on-screen text (e.g. \"en\", \"hi\", \"hi-Latn\" for romanized Hindi/Hinglish, \"es\"), or null if onScreenText is null"
}$$, true)
ON CONFLICT DO NOTHING;
