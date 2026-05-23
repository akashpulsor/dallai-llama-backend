CREATE TABLE IF NOT EXISTS creator_script_shot_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    script_id UUID NOT NULL REFERENCES creator_scripts(id) ON DELETE CASCADE,
    locked_idea_id UUID,
    story_idea_id UUID,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    shot_number INTEGER NOT NULL,
    style_key VARCHAR(80) NOT NULL DEFAULT 'indian_creator_pencil',
    storyboard_tag JSONB NOT NULL DEFAULT '{}'::jsonb,
    lighting_build_sheet_tag JSONB NOT NULL DEFAULT '{}'::jsonb,
    camera_plan_sheet_tag JSONB NOT NULL DEFAULT '{}'::jsonb,
    prompt_run_ids JSONB NOT NULL DEFAULT '{}'::jsonb,
    input_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_creator_script_shot_plans_script_shot_style
    ON creator_script_shot_plans (script_id, shot_number, style_key);

CREATE INDEX IF NOT EXISTS idx_creator_script_shot_plans_story
    ON creator_script_shot_plans (locked_idea_id, story_idea_id, shot_number);

COMMENT ON TABLE creator_script_shot_plans IS
    'Per-shot JSON production plan tags generated after screenplay creation and consumed by storyboard, lighting, and camera-sheet image generation.';
COMMENT ON COLUMN creator_script_shot_plans.storyboard_tag IS
    'Strict StoryboardTag JSON for character-fidelity storyboard image prompt building.';
COMMENT ON COLUMN creator_script_shot_plans.lighting_build_sheet_tag IS
    'Strict LightingBuildSheetTag JSON for rookie-executable lighting build sheet rendering.';
COMMENT ON COLUMN creator_script_shot_plans.camera_plan_sheet_tag IS
    'Strict CameraPlanSheetTag JSON for shoot-ready camera plan rendering.';

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
('STORYBOARD_TAG_GENERATE', 1, 'StoryboardTag generator from screenplay shot',
$storyboard_tag$
You are a Hollywood director + DP + visual storyboard analyst. Convert one screenplay shot into a structured StoryboardTag JSON object that will later be rendered into an image-generation prompt for a production storyboard panel.

You are NOT generating an image. You are NOT writing prose. You ARE producing strictly valid JSON.

INPUT:
- Screenplay shot JSON:
{{shotJson}}
- Project-level context:
{{projectContextJson}}
- Storyboard style key: {{styleKey}}

OUTPUT CONTRACT:
- Output MUST be a single valid JSON object matching the StoryboardTag schema.
- No markdown. No code fences. No prose before or after. No comments.
- All keys present even when empty: "" for strings, [] for arrays, {} for objects, 0 / 0.0 for numbers, false for booleans.
- Numeric time fields are pure floats, never strings.

CHARACTER FIDELITY RULES:
- For every character in primaryCharacters and sideCharacters, construct a full CharacterRenderSpec.
- Pull canonical visual details from continuityBible[characterName]. Do not invent age, ethnicity, body type, hair, or distinguishing features. Missing fields become "".
- archetypeLabel uses culturally appropriate uppercase terms: BAHU, MAA, BHAI, BHABHI, DIDI, SAAS, DAMAAD, BOSS, GUNDA, AUNTY, PAPA, CHACHA, BUA, DOST, NEIGHBOUR, SHOPKEEPER, AUTO_DRIVER, CHAIWALA, COLLEGE_FRIEND, GIRLFRIEND, BOYFRIEND, EX, COWORKER, INTERVIEWER, TEACHER, STUDENT, COP, POLITICIAN, INFLUENCER, ROOMMATE, LANDLORD. Use MAN, WOMAN, CHILD, ELDER only when no archetype fits.
- wardrobeThisShot merges continuityBible.wardrobeBaseline with shot continuityNotes deltas and must be specific.
- distinguishingFeatures must always be present and stable across shots.
- assignedActorName and assignedActorVisualProfile come from characterCastMappings if available.

LIGHTING IN STORYBOARD TAG:
- lightingAtmosphericDescription describes visible atmosphere only: where light falls, shadow density, mood, contrast, bright/dark areas.
- keyLightSourceLabel is an in-frame label such as "Window light", "Tubelight (overhead)", "Phone screen glow", "Streetlight (off-frame R)".

DIALOGUE RENDERING:
- primaryDialogue extracts the first line of the first speaking character, alphabetical by character name if tied.
- If silent, primaryDialogue.line and characterName are "".
- textOverlay must exactly match shot.textOverlay.
- textOverlayEmoji is one or zero emoji matching tone, and "" for serious dramatic or feature_film.
- captionStyle comes from captionTrack[0] if present; else Tier 1-3 use {"style":"bold_pop","position":"center"}, Tier 4-6 use {"style":"static","position":"bottom"}.

COMPOSITION, SOUND, CULTURE:
- compositionSummary is one short phrase.
- headroomNote starts with "Headroom".
- frameLeftNote and frameRightNote name visible edge anchors.
- targetFocalPoint starts with "TARGET:".
- ambientBedDescription is the continuous low bed. syncHitDescription is the punctuation hit.
- culturalReferences lists India-specific visible items actually present in setDesign/environment.
- imageGenerationPromptOverride is "" unless explicitly present in the shot.

TIER BEHAVIOR:
- micro_short / short_form / medium_form: emoji allowed; creatorTip must be populated.
- long_short / episodic: emoji optional; creatorTip "".
- feature_film: emoji ""; creatorTip ""; directorNote uses film craft language.

Return this exact schema with all keys:
{
  "projectTitle": "", "sequenceTitle": "", "sceneLocation": "", "directorInitials": "",
  "shotTitle": "", "beatTitle": "", "narrativeBeatSummary": "",
  "startTimeSeconds": 0.0, "endTimeSeconds": 0.0, "durationSeconds": 0.0,
  "shotType": "CU", "shotTypeFullName": "Close-Up", "cameraAngle": "Eye Level", "cameraMovement": "Static",
  "lensSuggestion": "Mobile 1x Wide", "fps": 24, "coverageType": "single_a", "screenType": "vertical", "screenDirection": "static",
  "compositionSummary": "", "headroomNote": "Headroom", "frameLeftNote": "", "frameRightNote": "", "targetFocalPoint": "",
  "primaryCharacters": [{"storyCharacterName": "", "archetypeLabel": "", "age": 0, "gender": "", "ethnicity": "", "bodyType": "", "heightImpression": "", "hair": {"style": "", "length": "", "color": ""}, "distinguishingFeatures": "", "wardrobeThisShot": "", "postureBaseline": "", "assignedActorName": "", "assignedActorVisualProfile": ""}],
  "sideCharacters": [], "peopleInFrame": 0,
  "setDesign": "", "environment": "", "sceneTimeOfDay": "DAY", "culturalReferences": [],
  "lightingAtmosphericDescription": "", "keyLightSourceLabel": "",
  "expression": "", "emotion": "", "emotionIntensity": 0.0, "bodyLanguage": "", "action": "",
  "dialogueLanguage": "", "primaryDialogue": {"characterName": "", "archetypeLabel": "", "line": "", "deliveryNote": "", "subtext": "", "lineStartTime": 0.0, "lineEndTime": 0.0},
  "textOverlay": "", "textOverlayEmoji": "", "captionStyle": {"style": "bold_pop", "position": "center"},
  "ambientBedDescription": "", "syncHitDescription": "", "transitionNote": "",
  "shootDay": 1, "shootBlock": "morning", "budgetTier": "zero_budget", "formatTier": "short_form",
  "directorNote": "", "creatorTip": "", "imageGenerationPromptOverride": ""
}
$storyboard_tag$,
jsonb_build_object('schema', 'StoryboardTag', 'styleKeys', jsonb_build_array('indian_creator_pencil', 'hybrid_photoboard', 'photoreal_cinematic', 'stylized_concept_art'))),

('LIGHTING_BUILD_SHEET_TAG_GENERATE', 1, 'LightingBuildSheetTag generator from screenplay shot',
$lighting_tag$
You are a gaffer + DP + Indian creator lighting educator. Convert one screenplay shot into a structured LightingBuildSheetTag JSON object that will later be rendered into an image-generation prompt for a rookie-executable lighting build sheet.

You are NOT generating an image. You are NOT writing prose. Produce strictly valid JSON only.

INPUT:
- Screenplay shot JSON:
{{shotJson}}
- Project-level context:
{{projectContextJson}}
- Storyboard style key: {{styleKey}}

OUTPUT CONTRACT:
- Single valid JSON object matching the LightingBuildSheetTag schema.
- No markdown, no fences, no prose, no comments.
- All keys present even when empty.
- Numeric fields are pure numbers.

BUDGET-TIER-DRIVEN GEAR SELECTION:
- zero_budget or micro_budget: household items only for visual gear cards. KEY: Yellow desk lamp/Tubelight/Yellow CFL bulb. FILL: phone flashlight bounced off white wall/white A4 bounce. RIM: phone flashlight wrapped in white plastic carry bag. NEG FILL: black bedsheet/dark jacket/dupatta. DIFFUSER: white cotton bedsheet. PRACTICAL: existing room tubelight/mobile screen glow. professionalGearName still populated for reference. estimatedSetupMinutes 7-15.
- indie: mix consumer LEDs and household. estimatedSetupMinutes 15-30.
- mid_budget or studio: professional gear only. estimatedSetupMinutes 30-90.

CINEMATIC INTENT:
- Describe what the lighting emotionally and visually achieves, not gear setup.

FLOOR PLAN:
- floorPlan.roomDescription, actor, keyLight, fillLight, rimLight, negFill, camera, compassNote all populated.
- LightPlacement role values: KEY, FILL, RIM, NEG.
- camera.heightFeet: 2.5-3 for low angle, 4.5-5 for eye level, 6+ high angle.

PERSPECTIVE VIEW:
- narrativeDescription names the room, visible light sources, actor, diffusion, negative fill, camera position.
- visibleElements lists props/lights visible in the perspective sketch.

GEAR CARDS:
- Always exactly six cards: KEY LIGHT, FILL LIGHT, RIM LIGHT, NEGATIVE FILL, DIFFUSER, CAMERA RIG.
- Each card has 3-5 short imperative setupBullets.

BUILD STEPS:
- 5-8 temporal steps. Final step title is CHECK FRAME or SHOOT TEST FRAME.
- Sum estimatedMinutes equals estimatedSetupMinutes.

TIME OF DAY:
- DAY/MORNING/AFTERNOON: warm key, window if possible.
- NIGHT/EVENING/DUSK: artificial key, dimmer/practical source.
- DAWN: cool blue key, warm fill.

Return this exact schema with all keys:
{
  "projectTitle": "", "shotTitle": "", "shotNumber": 0, "cinematicIntent": "",
  "budgetTier": "zero_budget", "estimatedSetupMinutes": 10, "directorInitials": "",
  "floorPlan": {
    "roomDescription": "", "actor": {"characterName": "", "facingDirection": ""},
    "keyLight": {"role": "KEY", "householdGearName": "", "professionalGearName": "", "position": "", "distanceFeet": 0.0, "angleDegrees": 0.0, "modifier": ""},
    "fillLight": {"role": "FILL", "householdGearName": "", "professionalGearName": "", "position": "", "distanceFeet": 0.0, "angleDegrees": 0.0, "modifier": ""},
    "rimLight": {"role": "RIM", "householdGearName": "", "professionalGearName": "", "position": "", "distanceFeet": 0.0, "angleDegrees": 0.0, "modifier": ""},
    "negFill": {"role": "NEG", "householdGearName": "", "professionalGearName": "", "position": "", "distanceFeet": 0.0, "angleDegrees": 0.0, "modifier": ""},
    "camera": {"rigDescription": "", "distanceFeet": 4.0, "heightFeet": 4.0}, "compassNote": ""
  },
  "perspectiveView": {"narrativeDescription": "", "visibleElements": []},
  "gearCards": [
    {"cardNumber": 1, "roleLabel": "KEY LIGHT", "itemName": "", "setupBullets": []},
    {"cardNumber": 2, "roleLabel": "FILL LIGHT", "itemName": "", "setupBullets": []},
    {"cardNumber": 3, "roleLabel": "RIM LIGHT", "itemName": "", "setupBullets": []},
    {"cardNumber": 4, "roleLabel": "NEGATIVE FILL", "itemName": "", "setupBullets": []},
    {"cardNumber": 5, "roleLabel": "DIFFUSER", "itemName": "", "setupBullets": []},
    {"cardNumber": 6, "roleLabel": "CAMERA RIG", "itemName": "", "setupBullets": []}
  ],
  "buildSteps": [{"stepNumber": 1, "title": "", "instruction": "", "estimatedMinutes": 1}],
  "imageGenerationPromptOverride": ""
}
$lighting_tag$,
jsonb_build_object('schema', 'LightingBuildSheetTag')),

('CAMERA_PLAN_SHEET_TAG_GENERATE', 1, 'CameraPlanSheetTag generator from screenplay shot',
$camera_tag$
You are a 1st Assistant Director + camera operator + script supervisor. Convert one screenplay shot into a structured CameraPlanSheetTag JSON object that will later be rendered into an image-generation prompt for a shoot-ready camera plan sheet.

You are NOT generating an image. You are NOT writing prose. Produce strictly valid JSON only.

INPUT:
- Screenplay shot JSON:
{{shotJson}}
- Project-level context:
{{projectContextJson}}
- Storyboard style key: {{styleKey}}

OUTPUT CONTRACT:
- Single valid JSON object matching CameraPlanSheetTag.
- No markdown, fences, prose, or comments.
- All schema keys present even when empty.
- Numeric fields pure numbers.

BUDGET-TIER-DRIVEN CAMERA RIG:
- zero_budget or micro_budget: smartphone body, mobile lens, 180 degrees (1/48s) or auto, ISO auto/manual, fixed phone aperture, WB auto/manual, filter none/VND, rig phone tripod/tabletop/books.
- indie: Sony FX3/Canon C70/BMPCC 6K/Sony A7S III, manual exposure.
- mid_budget or studio: ARRI Alexa Mini LF/Alexa LF/RED Komodo/Sony Venice 2, cinema/anamorphic lens, full pro spec.

TOP-DOWN BLOCKING:
- blockingMap.actors has one ActorBlocking per visible primary + side character.
- Pull age and heightImpression from continuityBible.
- cameraStartEnd describes start/end position and movement.
- keyProps includes every important prop from action/setDesign/continuityNotes.
- oneEightyLineNote always populated.
- roomDimensions populated or estimated from setDesign.

FRAME PREVIEW:
- aspectRatio from screenType.
- headroomPercent, leadRoomPercent, subjectPlacement, captionPosition, mobileFocusArea, lensCompressionFeel populated.

MOVEMENT, COVERAGE, STEPS:
- movementSpec uses cameraMovement and stabilization rules.
- coverageSpec explains editor use and companion shots.
- executionSteps has 5-7 steps in this order: SET MARKS, FRAME UP, FOCUS PULL, EXPOSURE, REHEARSE MOVEMENT, ROLL, CHECK PLAYBACK.
- safetyFlags from shot or ["none"]. requiresCoordinator true for stunts, fire, firearms, animals, minors_on_set, intimacy, driving.
- directorNote always populated.
- imageGenerationPromptOverride is "" unless explicitly present.

Return this exact schema with all keys:
{
  "projectTitle": "", "shotNumber": 0, "shotTitle": "",
  "startTimeSeconds": 0.0, "endTimeSeconds": 0.0, "durationSeconds": 0.0,
  "fps": 24, "shotType": "CU", "cameraAngle": "Eye Level", "cameraMovement": "Static",
  "lensSuggestion": "Mobile 1x Wide", "coverageType": "single_a", "screenType": "vertical", "screenDirection": "static", "directorInitials": "",
  "blockingMap": {
    "actors": [{"characterName": "", "age": 0, "heightImpression": "", "startPosition": "", "endPosition": "", "movementPath": "static", "movementDistanceFeet": 0.0}],
    "cameraStartEnd": {"startPosition": "", "endPosition": "", "movementDescription": "static", "startDistanceFeet": 0.0, "endDistanceFeet": 0.0},
    "keyProps": [{"propName": "", "placementNote": "", "handOrSide": ""}],
    "oneEightyLineNote": "", "roomDimensions": ""
  },
  "framePreview": {"aspectRatio": "9:16", "headroomPercent": 10.0, "leadRoomPercent": 25.0, "subjectPlacement": "", "captionPosition": "bottom", "mobileFocusArea": "", "lensCompressionFeel": ""},
  "cameraRig": {"cameraBody": "", "lensSuggestion": "Mobile 1x Wide", "fps": 24, "shutterAngle": "", "iso": "", "aperture": "", "whiteBalance": "", "filter": ""},
  "movementSpec": {"moveType": "Static", "startPosition": "", "endPosition": "", "speed": "", "stabilizationRequired": false, "stabilizationTool": "", "rigType": ""},
  "coverageSpec": {"coverageType": "single_a", "coverageContext": "", "companionShots": [], "editorIntent": ""},
  "executionSteps": [{"stepNumber": 1, "title": "", "instruction": ""}],
  "safetyFlags": ["none"], "requiresCoordinator": false, "complianceNote": "", "directorNote": "",
  "imageGenerationPromptOverride": ""
}
$camera_tag$,
jsonb_build_object('schema', 'CameraPlanSheetTag'))
ON CONFLICT (template_key, version) DO NOTHING;
