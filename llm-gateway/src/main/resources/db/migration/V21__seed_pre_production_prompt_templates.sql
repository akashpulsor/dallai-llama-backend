-- pre-production-service owns no prompt_template rows of its own -- llm-gateway is the single
-- registry every service's task-specific prompts go through (see PromptTemplateService).
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCRIPT_GENERATE', 1, $$You are an AI cinematic script writer for short-form vertical video, in the same tradition as a professional short-form creative planning engine: prioritize realism, retention, emotional clarity, and beginner-friendly execution.

Brief: {{brief}}
Target duration seconds: {{durationSeconds}}

Write a short narrative script for this brief, plan its narrative shape, and extract every named or clearly implied character.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "scriptText": "the full script text",
  "pacingStyle": "one short phrase describing the pacing (e.g. fast-cut hook then slow reveal)",
  "emotionalArc": "one or two sentences describing how the emotional tone moves from start to finish",
  "hookStrategy": "one or two sentences describing how the first 3 seconds create tension, curiosity, or a visual hook",
  "characters": [
    { "characterKey": "lowercase_snake_case_id", "characterName": "Display Name", "characterRole": "protagonist|supporting|narrator|etc", "description": "brief visual/personality description" }
  ]
}$$, true),

('PRE_PROD_SCREENPLAY_GENERATE', 1, $$You are an AI screenplay editor. Break the following script into a numbered scene list, and for each scene note which character it is emotionally about and why that scene exists in the story.

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
      "emotionalPurpose": "one sentence: why this scene exists in the story's emotional progression"
    }
  ]
}
Scene numbers must start at 1 and increase by 1 with no gaps.$$, true),

('PRE_PROD_SHOT_LIST_GENERATE', 1, $$You are an AI shot list breakdown artist and rookie-friendly filming director, in the same tradition as a professional short-form production planning engine. Break the following script into individual camera shots, grouped under the given scene numbers, with full below-the-line production detail so a solo smartphone creator with no crew can execute each shot.

Script:
{{scriptText}}

Known character keys: {{characterKeys}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "shots": [
    {
      "sceneNumber": 1,
      "shotNumber": 1,
      "shotType": "one of DIALOGUE, ACTION, PRODUCT_HERO, B_ROLL, TRANSITION",
      "scriptLine": "the exact line of dialogue or action this shot covers",
      "primaryCharacterKey": "a known character key, or null if no character is the focus",
      "cameraShotSize": "one of EWS, VWS, WS, MWS, MS, MCU, CU, ECU, INSERT, OTS",
      "cameraNote": "brief camera movement/framing note",
      "location": "short location name",
      "timeOfDay": "one of DAWN, GOLDEN_HOUR, MIDDAY, BLUE_HOUR, NIGHT, MAGIC_HOUR",
      "lightingMood": "one of HIGH_KEY, LOW_KEY, CHIAROSCURO, SOFT",
      "durationSeconds": 4,
      "aspectRatio": "one of RATIO_16_9, RATIO_9_16, RATIO_1_1, RATIO_4_5, RATIO_21_9",
      "cameraAngle": "e.g. low angle, eye level, high angle, dutch tilt",
      "cameraMovement": "e.g. static, slow push-in, handheld follow, pan left",
      "lensSuggestion": "e.g. wide 24mm equivalent, standard 50mm equivalent",
      "fps": 30,
      "composition": "framing/rule-of-thirds/leading-lines guidance for this shot",
      "expression": "the primary character's facial expression in this shot",
      "emotion": "the primary character's dominant emotion in this shot",
      "bodyLanguage": "posture/gesture guidance for the primary character",
      "action": "what physically happens on screen during this shot",
      "voiceOver": "voice-over line for this shot, or null",
      "textOverlay": "on-screen text for this shot, or null",
      "soundDesign": "ambient/foley/music cue notes for this shot",
      "editingNotes": "cut timing, speed ramp, or transition-in-edit notes",
      "retentionGoal": "what this shot is doing to keep the viewer watching",
      "creatorDirection": "plain-language direction for the person filming this shot",
      "subtitlePosition": "e.g. lower-third, upper-third, center",
      "mobileFocusArea": "where on a vertical 9:16 frame the viewer's eye should land",
      "safeZoneNotes": "what to keep clear of UI overlays (captions, profile icons, etc)",
      "executionDifficulty": "one of EASY, MEDIUM, HARD",
      "cinematicExecution": "the specific technical steps to achieve the intended look on a phone",
      "rookieFriendlyGuide": "a beginner-friendly, step-by-step version of cinematicExecution",
      "sketchPrompt": "a single-shot storyboard sketch prompt: camera angle, expression, environment, lighting, character action, grayscale pencil storyboard aesthetic, vertical composition"
    }
  ]
}
Use DIALOGUE only for shots where primaryCharacterKey speaks a line. Use PRODUCT_HERO only for shots that exist to showcase a product. shotNumber restarts at 1 within each sceneNumber.$$, true)
ON CONFLICT DO NOTHING;
