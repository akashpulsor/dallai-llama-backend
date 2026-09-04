-- Republishes PRE_PROD_SCREENPLAY_GENERATE as version 3 -- characterFocus was always free text,
-- never a real reference to which character(s) actually appear in a scene, so there was no way to
-- tell "this scene has no character in it (pure motion-graphic/B-roll)" from "the model forgot to
-- fill this in". characterKeys makes that explicit and maps onto a real relational join
-- (screenplay_scene_character) instead.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SCREENPLAY_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCREENPLAY_GENERATE', 3, $$You are an AI screenplay editor. Break the following script into a numbered scene list, and for each scene note which character(s) it is emotionally about and why that scene exists in the story.

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
      "characterFocus": "one short phrase naming who/what this scene is emotionally centered on, for display -- not necessarily a real character key",
      "characterKeys": ["the real characterKey(s) from the script's characters[] who are actually IN this scene -- empty array if this scene is pure motion-graphic/B-roll/product footage with no character in it, do not force one"],
      "emotionalPurpose": "one sentence: why this scene exists in the story's emotional progression",
      "estimatedSeconds": 4
    }
  ]
}
Scene numbers must start at 1 and increase by 1 with no gaps. estimatedSeconds is your best estimate of how long this scene should play on screen, in whole seconds. A NARRATOR-typed character is never in characterKeys for any scene (narration is heard, not seen) -- reference them only in dialogue/voice-over content, not as a scene participant.$$, true)
ON CONFLICT DO NOTHING;
