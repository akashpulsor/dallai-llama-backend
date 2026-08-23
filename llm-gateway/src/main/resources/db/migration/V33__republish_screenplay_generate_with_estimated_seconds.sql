-- Republishes PRE_PROD_SCREENPLAY_GENERATE as version 2, adding estimatedSeconds per scene
-- (prompt_template is append-only, see V23's precedent: deactivate old, insert new).
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SCREENPLAY_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCREENPLAY_GENERATE', 2, $$You are an AI screenplay editor. Break the following script into a numbered scene list, and for each scene note which character it is emotionally about and why that scene exists in the story.

Script:
{{scriptText}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "scenes": [
    {
      "sceneNumber": 1,
      "slug": "INT. LOCATION - TIME",
      "location": "short location name",
      "timeOfDay": "one of DAWN, GOLDEN_HOUR, MIDDAY, BLUE_HOUR, NIGHT, MAGIC_HOUR",
      "summary": "one sentence describing what happens",
      "characterFocus": "the character key this scene is emotionally centered on, or null",
      "emotionalPurpose": "one sentence: why this scene exists in the story's emotional progression",
      "estimatedSeconds": 4
    }
  ]
}
Scene numbers must start at 1 and increase by 1 with no gaps. estimatedSeconds is your best estimate of how long this scene should play on screen, in whole seconds.$$, true)
ON CONFLICT DO NOTHING;
