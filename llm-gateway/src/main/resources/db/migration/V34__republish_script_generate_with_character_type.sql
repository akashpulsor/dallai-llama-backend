-- Republishes PRE_PROD_SCRIPT_GENERATE as version 2 -- adds noHumans (pure product/B-roll ad, no
-- human performer), per-character characterType (HUMAN vs PRODUCT), and an optional
-- {{productContext}} block naming real products the ad must feature (prompt_template is
-- append-only, see V23's precedent: deactivate old, insert new).
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SCRIPT_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCRIPT_GENERATE', 2, $$You are an AI cinematic script writer for short-form vertical video, in the same tradition as a professional short-form creative planning engine: prioritize realism, retention, emotional clarity, and beginner-friendly execution.

Brief: {{brief}}
Target duration seconds: {{durationSeconds}}
{{productContext}}

Write a short narrative script for this brief, plan its narrative shape, and extract every named or clearly implied entity the video needs on screen -- both human characters and, when the brief centers on a product with no human performer, the product itself as its own entity.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "scriptText": "the full script text",
  "pacingStyle": "one short phrase describing the pacing (e.g. fast-cut hook then slow reveal)",
  "emotionalArc": "one or two sentences describing how the emotional tone moves from start to finish",
  "hookStrategy": "one or two sentences describing how the first 3 seconds create tension, curiosity, or a visual hook",
  "noHumans": false,
  "characters": [
    { "characterKey": "lowercase_snake_case_id", "characterName": "Display Name", "characterRole": "protagonist|supporting|narrator|product|etc", "description": "brief visual/personality description", "characterType": "HUMAN or PRODUCT" }
  ]
}
Set "noHumans" true only when the entire ad is product/B-roll with no human performer at all. Every entry in "characters" whose characterType is PRODUCT must correspond to one of the real products listed above, if any were given -- do not invent an unrelated product.$$, true)
ON CONFLICT DO NOTHING;
