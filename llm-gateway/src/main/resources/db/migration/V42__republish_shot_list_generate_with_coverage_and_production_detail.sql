-- Republishes PRE_PROD_SHOT_LIST_GENERATE as version 4 -- adds coverageType/screenDirection/
-- peopleInFrame/culturalReferences/productShotType/shootDay/shootBlock/directorNote, restoring
-- fields creator-service's real StoryboardTag had that this prompt never asked for. Everything
-- else (cinematography block, aspect ratio directive, motion graphics guidance) is unchanged from
-- v3. prompt_template is append-only, same precedent as every prior republish.
UPDATE prompt_template SET active = false WHERE task_key = 'PRE_PROD_SHOT_LIST_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SHOT_LIST_GENERATE', 4, $$You are an AI shot list breakdown artist and rookie-friendly filming director, in the same tradition as a professional short-form production planning engine. Break the following script into individual camera shots, grouped under the given scene numbers, with full below-the-line production detail so a solo smartphone creator with no crew can execute each shot.

Script:
{{scriptText}}

Known character keys: {{characterKeys}}
Target aspect ratio: {{aspectRatio}}
Motion graphics guidance: {{motionGraphicsGuidance}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "shots": [
    {
      "sceneNumber": 1,
      "shotNumber": 1,
      "shotType": "one of DIALOGUE, ACTION, PRODUCT_HERO, B_ROLL, TRANSITION, MOTION_GRAPHIC",
      "scriptLine": "the exact line of dialogue or action this shot covers",
      "primaryCharacterKey": "a known character key, or null if no character is the focus",
      "cameraShotSize": "one of EWS, VWS, WS, MWS, MS, MCU, CU, ECU, INSERT, OTS",
      "cameraNote": "brief camera movement/framing note",
      "location": "short location name",
      "timeOfDay": "one of DAWN, GOLDEN_HOUR, MIDDAY, BLUE_HOUR, NIGHT, MAGIC_HOUR",
      "lightingMood": "one of HIGH_KEY, LOW_KEY, CHIAROSCURO, SOFT",
      "durationSeconds": 4,
      "aspectRatio": "one of RATIO_16_9, RATIO_9_16, RATIO_1_1, RATIO_4_5, RATIO_21_9 -- use the target aspect ratio above unless this specific shot has a deliberate reason to differ",
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
      "sketchPrompt": "a single-shot storyboard sketch prompt: camera angle, expression, environment, lighting, character action, grayscale pencil storyboard aesthetic, vertical composition",
      "coverageType": "e.g. master, coverage, insert, cutaway -- what role this shot plays in editing coverage",
      "screenDirection": "e.g. left-to-right, right-to-left, toward camera -- the direction of movement/gaze in frame, for continuity across cuts",
      "peopleInFrame": "the exact number of visible people in this shot, 0 if none",
      "culturalReferences": "any specific cultural, regional, or contextual reference this shot depends on, or null",
      "productShotType": "PRODUCT_HERO shots only: one of Hero Shot, Ingredient Shot, Lifestyle Shot, Pack Shot, or a similarly specific marketing category; null for non-product shots",
      "shootDay": "which shoot day this belongs to if the project spans multiple days, e.g. 'Day 1', or null for a single-day shoot",
      "shootBlock": "e.g. morning, afternoon, evening, night -- when in the shoot day this should be filmed",
      "directorNote": "one specific note from the director's point of view for this shot, or null",
      "cinematography": {
        "cameraBody": "camera body class this shot is conceived for, e.g. smartphone, mirrorless, cinema camera",
        "sensor": "sensor size/format if relevant, e.g. 1-inch, full-frame, Super 35",
        "captureFormat": "e.g. 4K 10-bit log, standard 1080p",
        "recordingCharacteristics": "any codec/dynamic-range note relevant to the look",
        "positionHeight": "camera height relative to subject, e.g. eye level, low, high, ground level",
        "positionDistance": "camera distance from subject, e.g. arm's length, 2 meters, macro-close",
        "positionLateral": "left/right/center offset from subject",
        "positionElevation": "elevation relative to environment, e.g. ground level, elevated, aerial",
        "positionOrientation": "camera orientation relative to subject/action axis",
        "lensFocalLength": "e.g. 24mm, 50mm, 85mm equivalent",
        "lensType": "prime or zoom",
        "lensOpticalFormat": "spherical or anamorphic",
        "lensDistortion": "expected distortion character at this focal length/distance",
        "lensCompression": "expected perspective compression character",
        "lensCharacter": "any notable lens rendering character (softness, bokeh, flare tendency)",
        "framing": "overall frame composition description",
        "subjectPlacement": "where the subject sits in frame, e.g. centered, rule-of-thirds left",
        "headroom": "amount of headroom above the subject",
        "leadRoom": "amount of lead room in the direction of movement/gaze",
        "visualBalance": "how the frame's visual weight is balanced",
        "focusTarget": "what the lens is focused on",
        "focusDistance": "approximate focus distance",
        "depthOfField": "shallow, medium, or deep",
        "rackFocus": "whether/how focus shifts during the shot, or null if static",
        "focusBehaviour": "any other focus-pulling behavior note",
        "movementType": "e.g. static, pan, tilt, dolly, truck, handheld, gimbal float",
        "movementTrajectory": "the path the camera travels, if moving",
        "movementSpeed": "e.g. slow, medium, fast",
        "movementAcceleration": "e.g. ease-in, ease-out, constant",
        "movementRotation": "any rotational component, e.g. slight tilt during push-in",
        "movementSubjectRelationship": "how the movement relates to the subject, e.g. leads it, follows it, orbits it",
        "support": "e.g. handheld, tripod, gimbal, slider/dolly, drone",
        "aperture": "suggested aperture/depth-of-field control, e.g. wide open, stopped down",
        "iso": "suggested ISO/gain range, e.g. low-light high ISO, base ISO daylight",
        "shutter": "suggested shutter speed/angle description",
        "ndFilter": "ND filtration note if relevant to exposure",
        "dynamicRange": "expected dynamic range demand of the scene, e.g. high-contrast, flat lighting",
        "shutterAngle": "e.g. 180 degrees standard, narrower for a crisper look",
        "motionBlur": "expected motion blur character",
        "slowMotion": "whether this shot is slow motion and at what relative speed, or null",
        "filtrationDiffusion": "any diffusion filter look, or null",
        "filtrationNd": "any additional ND filtration note beyond exposure control, or null",
        "filtrationPolarizer": "polarizer use if relevant, or null",
        "filtrationSpecialty": "any specialty filter (star, mist, etc), or null",
        "contrast": "intended contrast character",
        "colorResponse": "intended color rendering character",
        "grain": "intended grain/noise character",
        "halation": "intended halation character around highlights, or null",
        "bloom": "intended highlight bloom character, or null",
        "sharpness": "intended sharpness character",
        "flare": "intended lens flare character, or null"
      }
    }
  ]
}
Only fill in cinematography fields that are actually meaningful for this shot -- use null for anything not relevant rather than inventing detail. Use DIALOGUE only for shots where primaryCharacterKey speaks a line. Use PRODUCT_HERO only for shots that exist to showcase a product. Use MOTION_GRAPHIC for text/data/graphic-driven beats per the motion graphics guidance above -- leave cinematography fields null for MOTION_GRAPHIC shots, they aren't camera-planned. Assign productShotType only for PRODUCT_HERO shots, and make sure exactly one shot is "Hero Shot" and, if the project has a clear final product beat, exactly one is "Pack Shot". shotNumber restarts at 1 within each sceneNumber.$$, true)
ON CONFLICT DO NOTHING;
