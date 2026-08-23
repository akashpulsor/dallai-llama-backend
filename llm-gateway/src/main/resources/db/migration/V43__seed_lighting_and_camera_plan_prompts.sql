-- Restores creator-service's real LIGHTING_BUILD_SHEET_TAG_GENERATE / CAMERA_PLAN_SHEET_TAG_GENERATE
-- patterns -- structured plans that feed the LIGHTING/CAMERA_PLAN shot image prompts instead of
-- just the shot's flat fields.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_LIGHTING_PLAN_GENERATE', 1, $$You are a gaffer and lighting educator planning a rookie-executable lighting setup for one shot, for a solo smartphone creator with no crew and household-adjacent gear.

Lighting mood: {{lightingMood}}
Location: {{location}}
Time of day: {{timeOfDay}}
What happens: {{action}}

Plan exactly 6 gear slots (key, fill, rim, negative fill, diffuser, camera rig) using affordable, commonly-available gear -- household substitutions are fine and encouraged (e.g. a desk lamp as key, a phone flashlight as fill/rim, a dark cloth as negative fill, a bedsheet as diffuser).

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
('PRE_PROD_CAMERA_PLAN_GENERATE', 1, $$You are a 1st Assistant Director and camera operator planning a shoot-ready camera setup for one shot, for a solo smartphone creator with no crew.

Camera angle: {{cameraAngle}}
Camera movement: {{cameraMovement}}
Shot size: {{cameraShotSize}}
What happens: {{action}}

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
