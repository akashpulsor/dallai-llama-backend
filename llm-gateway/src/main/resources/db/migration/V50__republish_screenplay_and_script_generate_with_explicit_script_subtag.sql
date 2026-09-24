-- Republishes PRE_PROD_SCREENPLAY_GENERATE as version 4 and PRE_PROD_SCRIPT_GENERATE as version 7
-- to fix the "Devanagari script even when the creator wanted Latin-Hindi (hi-Latn-IN)" bug:
--
--   1. The screenplay template did not reference {{dialogueLanguage}} at all -- the LLM had zero
--      language guidance at the screenplay stage and switched scripts on its own. Now the same
--      variable used by script generation flows through here too.
--
--   2. Both templates previously just said "write in the dialogue language given above". The LLM
--      collapsed BCP-47 codes like "hi-Latn-IN" and "hi-IN" to the same "Hindi" concept and
--      defaulted to Devanagari every time. The new instruction interprets the script subtag
--      literally: "-Latn-" means Latin-alphabet transliteration only, absent script subtag means
--      the language's native script.
--
-- Nothing else in either template changed -- diff is scoped to language handling.

UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SCREENPLAY_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCREENPLAY_GENERATE', 4, $$You are an AI screenplay editor. Break the following script into a numbered scene list, and for each scene note which character(s) it is emotionally about and why that scene exists in the story.

Script:
{{scriptText}}

Dialogue language (BCP-47): {{dialogueLanguage}}

Every scene's dialogue, voice-over, and any prose in "summary"/"emotionalPurpose" must be written in the dialogue language above. Interpret the BCP-47 code literally:
- A "-Latn-" script subtag (e.g. hi-Latn-IN, ur-Latn-IN) means Latin alphabet ONLY -- transliterate, do not switch to Devanagari, Nastaliq, Bengali, or any other native script.
- No script subtag (e.g. hi-IN, bn-IN, ta-IN, en-US) means write in that language's native script.
- Never mix scripts within the same field.

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

UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SCRIPT_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCRIPT_GENERATE', 7, $$You are an AI cinematic script writer for short-form vertical video, in the same tradition as a professional short-form creative planning engine: prioritize realism, retention, emotional clarity, and beginner-friendly execution.

Brief: {{brief}}
Target duration seconds: {{durationSeconds}}
Dialogue language (BCP-47): {{dialogueLanguage}}
{{productContext}}

Write a short narrative script for this brief. First plan its story structure (logline, central conflict, setting, how it ends), then the narrative shape (pacing, emotional arc, hook), then extract every named or clearly implied entity the video needs on screen -- both human characters (with real casting detail, not just a name) and, when the brief centers on a product with no human performer, the product itself as its own entity.

Write every line of spoken dialogue AND the scriptText itself in the dialogue language above. Interpret the BCP-47 code literally:
- A "-Latn-" script subtag (e.g. hi-Latn-IN, ur-Latn-IN) means Latin alphabet ONLY -- transliterate, do not switch to Devanagari, Nastaliq, Bengali, or any other native script.
- No script subtag (e.g. hi-IN, bn-IN, ta-IN, en-US) means write in that language's native script.
- Never mix scripts within the same field, and never default to English when the language is something else.

scriptText must be a comprehensive, complete narrative telling of the whole video's story -- cover the full arc from opening hook through to the ending payoff in prose, with enough detail that someone reading only this understands exactly what happens and why. Do not artificially compress it into a single terse sentence or two; write as much as the story genuinely needs. It is still not a shot-by-shot breakdown (that happens later, in shot list generation) and not a single-sentence logline (that is its own separate field below).

If the story has a narrator -- a voice that describes or comments on the story from outside it, never appearing on screen -- give that narrator its own entry in "characters" with characterType NARRATOR. This is different from a HUMAN character who is on screen and simply has a voice-over line in some shots: a NARRATOR is never in frame and never the subject of a shot's camera direction, only the source of narration audio.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "scriptText": "the full, comprehensive story -- the complete narrative, not a truncated summary",
  "pacingStyle": "one short phrase describing the pacing (e.g. fast-cut hook then slow reveal)",
  "emotionalArc": "one or two sentences describing how the emotional tone moves from start to finish",
  "hookStrategy": "one or two sentences describing how the first 3 seconds create tension, curiosity, or a visual hook",
  "hook": "the literal hook line or moment -- what actually plays/is said in the first 2-3 seconds, not a description of it",
  "logline": "one sentence: the whole story in a single line",
  "centralConflict": "one or two sentences: what tension or problem drives the story",
  "endingPayoff": "one or two sentences: how the story resolves and what it delivers",
  "setting": "one or two sentences: where and when this takes place",
  "storytellingType": "infer the best fit, e.g. narrator_visual_mix, talking_head_explainer, visual_voiceover, dialogue_scene, dramatic_scene -- do not force one of these if none fit, describe the actual approach instead",
  "noHumans": false,
  "characters": [
    {
      "characterKey": "lowercase_snake_case_id",
      "characterName": "Display Name",
      "characterRole": "protagonist|supporting|narrator|product|etc",
      "description": "brief visual/personality description",
      "characterType": "HUMAN, PRODUCT, or NARRATOR",
      "gender": "HUMAN or NARRATOR only, or null",
      "age": "HUMAN or NARRATOR only, a specific number, or null",
      "ageRange": "HUMAN or NARRATOR only, e.g. 'mid-20s', or null",
      "look": "HUMAN characters only (never NARRATOR -- never on screen): physical appearance detail, or null",
      "complexion": "HUMAN characters only (never NARRATOR): skin tone/complexion in a few words, or null",
      "profile": "HUMAN characters only: a short professional/social profile, or null",
      "persona": "HUMAN or NARRATOR: personality/temperament, or null",
      "backstory": "HUMAN or NARRATOR: brief relevant history, or null",
      "motivation": "HUMAN characters only: what they want in this story, or null",
      "fearOrBlock": "HUMAN characters only: what holds them back, or null",
      "relationshipToStory": "HUMAN or NARRATOR: how they relate to the central conflict or story, or null",
      "speakingStyle": "HUMAN or NARRATOR: how they talk/narrate, or null",
      "visualIdentity": "HUMAN characters only (never NARRATOR): distinguishing visual traits for consistent rendering across shots, or null"
    }
  ]
}
Set "noHumans" true only when the entire ad is product/B-roll with no human performer at all (a NARRATOR-only voice does not count as a human performer). Every entry in "characters" whose characterType is PRODUCT must correspond to one of the real products listed above, if any were given -- do not invent an unrelated product. Leave every HUMAN-only field null for PRODUCT-typed characters, and leave every on-screen-only field (look, complexion, visualIdentity) null for NARRATOR-typed characters.$$, true)
ON CONFLICT DO NOTHING;
