-- Republish PRE_PROD_SHOT_LIST_GENERATE as v9. Root cause: the v8 template added a
-- "LANGUAGE" preamble that lumped sketchPrompt into a list of "English descriptive
-- fields" alongside scriptLine/action/cameraNote/etc. After that change Gemini stopped
-- appending the styling suffix ("grayscale pencil storyboard aesthetic, vertical
-- composition") to sketchPrompt output -- treating it as a plain description like the
-- others rather than as an image prompt with load-bearing style keywords. Confirmed by
-- comparing DB values: 2026-09-07 (v7 era) avg 208 chars ending in styling suffix vs
-- 2026-09-21 (v8 era) avg 145 chars with suffix stripped.
--
-- v9:
--   1) sketchPrompt removed from the English descriptive fields list at the top.
--   2) sketchPrompt field schema now REQUIRES a verbatim styling prefix and explains
--      why (downstream image model produces photoreal frames without it).
-- Every other field is byte-for-byte identical to v8.
--
-- pre-production-service also has a render-time wrapAsStoryboardSketch guard
-- (ShotImageService, deployed pp 0.2.42) that prepends the same styling regardless of
-- what the LLM wrote -- this migration is defense-in-depth so newly-generated shots
-- get properly styled sketchPrompts in the DB too.

UPDATE prompt_template SET active = false
 WHERE task_key = 'PRE_PROD_SHOT_LIST_GENERATE' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active, created_at)
SELECT 'PRE_PROD_SHOT_LIST_GENERATE', 9, $$LANGUAGE -- read this first, it governs every field below.

Write EVERY descriptive field in English: scriptLine, action, cameraNote, composition, expression, bodyLanguage, emotion, editingNotes, creatorDirection, directorNote, cinematicExecution, screenDirection, location, soundDesign, retentionGoal, rookieFriendlyGuide, and every field inside the cinematography object. English regardless of what language the script below is written in.

Exactly two fields follow the project's language, which is {{dialogueLanguage}}: "voiceOver" (what is actually spoken) and "textOverlay" (what is actually shown on screen). Write those in {{dialogueLanguage}}, verbatim in its own script, never transliterated.

The distinction is what the field IS. A description tells a director and a video model what to make; those are read in English by both. Spoken lines and on-screen text are the finished product the audience sees and hears; those are in the audience's language. A Hindi camera note or a Hindi scene description is a bug -- it ends up inside an English video-generation prompt where nothing can act on it.

You are an AI shot list breakdown artist and rookie-friendly filming director, in the same tradition as a professional short-form production planning engine. Break the following script into individual camera shots, grouped under the given scene numbers, with full below-the-line production detail so a solo smartphone creator with no crew can execute each shot.

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
      "editingNotes": "the actual cut, written against this sequence's real neighbors, not a generic line: (1) the cut INTO this shot from the one before it -- name the technique (hard cut, J-cut, L-cut, match cut, whip pan, cross-dissolve, jump cut, smash cut) and why it's the right cut for this beat; (2) what carries across that cut for continuity -- screen direction, prop/costume/prop-in-hand state, eyeline, action continuing mid-motion; (3) the pacing rhythm here relative to the shots around it (holding longer to breathe vs. cutting fast to build energy) and any speed-ramp note. The first shot of the whole sequence describes only its own opening feel, not a cut from nothing.",
      "retentionGoal": "what this shot is doing to keep the viewer watching",
      "creatorDirection": "plain-language direction for the person filming this shot",
      "subtitlePosition": "e.g. lower-third, upper-third, center",
      "mobileFocusArea": "where on a vertical 9:16 frame the viewer's eye should land",
      "safeZoneNotes": "what to keep clear of UI overlays (captions, profile icons, etc)",
      "executionDifficulty": "one of EASY, MEDIUM, HARD",
      "cinematicExecution": "a SHORT PLAIN-TEXT STRING, 1-3 sentences, e.g. \"Handheld at chest height, slow push toward the subject's face as she looks up; keep the desk lamp key light just out of frame.\" This is NOT the cinematography object below and must never contain nested fields, key-value pairs, or JSON of its own -- it is prose describing the practical steps to get the shot's look on a phone, nothing more.",
      "rookieFriendlyGuide": "a beginner-friendly, step-by-step version of cinematicExecution",
      "sketchPrompt": "an image-generation prompt for the STORYBOARD panel. MUST begin verbatim with this exact styling prefix, no rewording: 'Storyboard sketch panel: pencil-and-ink black-and-white, hand-drawn look, loose but confident lines, cross-hatched shading, no color, no photorealism, thin outer border, vertical composition. Small inset frame in the corner showing the hero product close-up. Compact hand-lettered on-panel notes for camera angle and framing.' AFTER the prefix append the shot-specific scene text: camera angle, character expression, environment, lighting, character action. The prefix is not optional and must not be rephrased -- if it is missing, the downstream image model produces a photoreal frame instead of a storyboard sketch.",
      "coverageType": "e.g. master, coverage, insert, cutaway -- what role this shot plays in editing coverage",
      "screenDirection": "e.g. left-to-right, right-to-left, toward camera -- must stay consistent with the immediately preceding and following shot's screenDirection unless the cut is a deliberate direction change (in which case say so in editingNotes, don't just silently flip it)",
      "peopleInFrame": "the exact number of visible people in this shot, 0 if none",
      "culturalReferences": "any specific cultural, regional, or contextual reference this shot depends on, or null",
      "productShotType": "PRODUCT_HERO shots only: one of Hero Shot, Ingredient Shot, Lifestyle Shot, Pack Shot, or a similarly specific marketing category; null for non-product shots",
      "shootDay": "which shoot day this belongs to if the project spans multiple days, e.g. 'Day 1', or null for a single-day shoot",
      "shootBlock": "e.g. morning, afternoon, evening, night -- when in the shoot day this should be filmed",
      "directorNote": "one specific note from the director's point of view for this shot, or null",
      "cinematography": {
        "_comment_": "This is a SEPARATE object from cinematicExecution above -- do not skip this because you already wrote cinematicExecution, and do not put cinematicExecution's prose here.",
        "cameraBody": "camera body class this shot is conceived for, e.g. smartphone, mirrorless, cinema camera",
        "sensor": "sensor size/format if relevant, e.g. 1-inch, full-frame, Super 35",
        "captureFormat": "codec, bit depth and colour profile only -- e.g. 10-bit log, ProRes 422, standard dynamic range. NEVER a resolution or frame size: the output resolution is decided at render time and stating one here contradicts it.",
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
Only fill in cinematography fields that are actually meaningful for this shot -- use null for anything not relevant rather than inventing detail, but fill in at least cameraBody, positionHeight, positionDistance, lensFocalLength, movementType, and support for every shot: those six are always meaningful, never all-null. Use DIALOGUE only for shots where primaryCharacterKey speaks a line. Use PRODUCT_HERO only for shots that exist to showcase a product. Use MOTION_GRAPHIC for text/data/graphic-driven beats per the motion graphics guidance above -- leave cinematography fields null for MOTION_GRAPHIC shots, they aren't camera-planned. Assign productShotType only for PRODUCT_HERO shots, and make sure exactly one shot is "Hero Shot" and, if the project has a clear final product beat, exactly one is "Pack Shot". shotNumber restarts at 1 within each sceneNumber. Before writing editingNotes and screenDirection for any shot, look at the shot immediately before and after it in the full sequence you're producing -- these two fields are the only ones that describe a relationship between shots rather than the shot itself, and they should read that way: no two consecutive shots should have interchangeable editingNotes.$$, true, now();
