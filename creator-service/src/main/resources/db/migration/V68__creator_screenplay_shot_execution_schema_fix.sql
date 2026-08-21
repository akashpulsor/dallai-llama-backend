-- The SCRIPT_GENERATE prompt's own JSON example showed cinematicExecution/rookieFriendlyGuide
-- as bare empty strings ("") and never mentioned visualTreatment at all - meanwhile the Java DTO
-- (GeneratedScriptResponse.CinematicShot/CinematicExecution/RookieFriendlyGuide) has carried a
-- richer structured shape (zoomRecommendation, motionIntensity, whatIsThis, howToShoot, ...) for
-- some time. The model was correctly following the prompt's own (stale) example - not making a
-- judgment call to omit these fields. This brings the schema example back in sync with the DTO
-- and adds visualTreatment (previously absent from the schema entirely, so never generated).
INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Screenplay planner with structured shot-execution schema (cinematicExecution/rookieFriendlyGuide/visualTreatment)',
    replace(
        template_body,
        '      "cinematicExecution": "",
      "rookieFriendlyGuide": "",',
        '      "cinematicExecution": {"recommendedFPS": 24, "captureMode": "", "playbackSpeed": "", "cameraStyle": "", "stabilization": "", "transitionStyle": "", "zoomRecommendation": "", "motionIntensity": "", "editingComplexity": ""},
      "rookieFriendlyGuide": {"whatIsThis": "", "whyThisWorks": "", "howToShoot": [], "howToMoveCamera": [], "howToAct": [], "editingTip": "", "commonMistakes": [], "phoneOnlyFriendly": true},
      "visualTreatment": {"colorPalette": "", "filmLookReference": "", "textureNotes": "", "contrastMood": ""},'
    ) || chr(10) || $shotexec$

SHOT-EXECUTION SCHEMA RULES
- cinematicExecution, rookieFriendlyGuide, and visualTreatment must always be objects with the exact sub-fields shown in the schema above - never a plain string, never omitted.
- cinematicExecution.zoomRecommendation and motionIntensity are required on every shot, not optional flourishes - describe the actual zoom behavior (e.g. "static", "slow push-in") and motion intensity (e.g. "low - mostly static", "high - dynamic handheld") for that specific shot.
- rookieFriendlyGuide must give a beginner concrete, actionable steps (howToShoot, howToMoveCamera, howToAct as short imperative bullet arrays), not a single paragraph.
- visualTreatment must state this shot's actual color/film-look direction (colorPalette, filmLookReference, textureNotes, contrastMood) consistent with the project's overall tone - never leave these blank without a specific reason noted in directorNotes.
$shotexec$
    ,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'shotExecutionSchemaFixed', true
    )
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'SCRIPT_GENERATE')
  AND template_body LIKE '%"cinematicExecution": "",%'
ON CONFLICT (template_key, version) DO NOTHING;
