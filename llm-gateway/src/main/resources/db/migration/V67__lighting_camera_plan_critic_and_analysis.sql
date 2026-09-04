-- Adds the critique step LightingPlanService/CameraPlanService now run (same retry-with-feedback
-- shape as PRE_PROD_SCRIPT_CRITIC), and republishes the generate prompts to fold in a shot's
-- confirmed reference-image analysis (ShotProductReference) when one exists, instead of planning
-- from the shot's own flat fields alone.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_LIGHTING_PLAN_GENERATE' AND active = true;
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_CAMERA_PLAN_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_LIGHTING_PLAN_GENERATE', 2, $$You are a gaffer and lighting educator planning a rookie-executable lighting setup for one shot, for a solo smartphone creator with no crew and household-adjacent gear.

Lighting mood: {{lightingMood}}
Location: {{location}}
Time of day: {{timeOfDay}}
What happens: {{action}}

{{referenceAnalysis}}

Plan exactly 6 gear slots (key, fill, rim, negative fill, diffuser, camera rig) using affordable, commonly-available gear -- household substitutions are fine and encouraged (e.g. a desk lamp as key, a phone flashlight as fill/rim, a dark cloth as negative fill, a bedsheet as diffuser). If a reference image analysis is given above, plan toward matching its lighting style/mood, not just the shot's own flat fields.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "cinematicIntent": "one or two sentences: what this lighting setup is trying to achieve emotionally/visually",
  "estimatedSetupMinutes": 10,
  "keyLightGear": "specific gear + placement for the key light",
  "fillLightGear": "specific gear + placement for fill",
  "rimLightGear": "specific gear + placement for rim/back light, or null if not needed",
  "negFillGear": "specific gear + placement for negative fill, or null if not needed",
  "diffuserGear": "specific gear for diffusion, or null if not needed",
  "cameraRigGear": "what the camera/phone is mounted on",
  "buildSteps": "5-8 numbered setup steps as one string, newline-separated"
}$$, true)
ON CONFLICT DO NOTHING;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_CAMERA_PLAN_GENERATE', 2, $$You are a 1st Assistant Director and camera operator planning a shoot-ready camera setup for one shot, for a solo smartphone creator with no crew.

Camera angle: {{cameraAngle}}
Camera movement: {{cameraMovement}}
Shot size: {{cameraShotSize}}
What happens: {{action}}

{{referenceAnalysis}}

If a reference image analysis is given above, plan toward matching its camera angle/motion, not just the shot's own flat fields.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "blockingMap": "top-down description of camera position, subject position, and movement path",
  "executionSteps": "5-8 numbered steps as one string, newline-separated, in this order: SET MARKS, FRAME UP, FOCUS PULL, EXPOSURE, REHEARSE MOVEMENT, ROLL, CHECK PLAYBACK",
  "gimbalEnabled": false,
  "gimbalDevice": "specific gimbal/stabilizer if gimbalEnabled, else null",
  "gimbalMode": "e.g. follow, lock, or null if gimbalEnabled is false",
  "gimbalPanSpeed": "e.g. slow, medium, fast, or null",
  "gimbalTiltSpeed": "e.g. slow, medium, fast, or null",
  "safetyFlags": "any physical safety concern for this shot (traffic, height, water, etc), or null if none",
  "requiresCoordinator": false,
  "complianceNote": "any permission/location/compliance note relevant to filming this shot, or null"
}
Only set gimbalEnabled true when the camera movement genuinely calls for stabilized motion, not for a static shot. Only set requiresCoordinator true when safetyFlags describes a real physical risk.$$, true)
ON CONFLICT DO NOTHING;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_LIGHTING_PLAN_CRITIC', 1, $$You are a gaffer critiquing a lighting build sheet before a solo smartphone creator with no crew tries to actually build it, because regenerating a plan is cheap now and wasted setup time on set isn't.

Cinematic intent: {{cinematicIntent}}
Key light: {{keyLightGear}}
Fill light: {{fillLightGear}}
Build steps: {{buildSteps}}

Check: is every gear slot actually achievable with affordable/household gear (not professional-only equipment a solo creator won't have), do the build steps form a coherent, executable sequence, does the setup actually serve the stated cinematic intent.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "status": "PASS" | "WARN" | "FAIL",
  "issues": ["specific, actionable issue"]
}
Use FAIL only when a regeneration would clearly be better than fixing this one (e.g. gear that doesn't exist for a home setup, steps that contradict each other); use WARN for real but survivable issues; use PASS when this is genuinely executable. Keep issues specific and actionable.$$, true)
ON CONFLICT DO NOTHING;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_CAMERA_PLAN_CRITIC', 1, $$You are a 1st Assistant Director critiquing a camera/blocking plan before a solo smartphone creator with no crew tries to actually shoot it, because regenerating a plan is cheap now and a missed safety flag or an unshootable blocking map on set isn't.

Blocking map: {{blockingMap}}
Execution steps: {{executionSteps}}
Gimbal enabled: {{gimbalEnabled}}
Safety flags: {{safetyFlags}}

Check: is the blocking map physically coherent and shootable by one person, do the execution steps form a real, ordered sequence a solo creator can follow, is any genuine physical risk (traffic, height, water, crowds) actually flagged rather than missed.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "status": "PASS" | "WARN" | "FAIL",
  "issues": ["specific, actionable issue"]
}
Use FAIL only when a regeneration would clearly be better than fixing this one (e.g. a missed real safety risk, a blocking map that's physically impossible for one person to execute); use WARN for real but survivable issues; use PASS when this is genuinely shoot-ready. Keep issues specific and actionable.$$, true)
ON CONFLICT DO NOTHING;
