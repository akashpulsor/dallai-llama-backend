INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    'SCRIPT_GENERATE',
    11,
    'Master screenplay planner with strict audio and production schema',
    replace(
        replace(
            replace(
                template_body,
                '- Sound design must include one ambient_bed and one sync_hit.',
                '- Sound design must include one ambient_bed and one sync_hit.' || chr(10) ||
                '- Root screenplay must include soundDesignPlan and backgroundMusicPlan.' || chr(10) ||
                '- Every shot must include ambientBedDescription, syncHitDescription, and backgroundMusicCue.' || chr(10) ||
                '- backgroundMusicCue describes cueType, musicMood, startTimeSeconds, endTimeSeconds, durationSeconds, volumeLevel, duckUnderDialogue, and editNote.'
            ),
            '  "toneAnchors": [],' || chr(10) || '  "shootingSchedule": [],',
            '  "toneAnchors": [],' || chr(10) ||
            '  "soundDesignPlan": {"mixIntent":"","ambientBedStrategy":"","syncHitStrategy":"","dialogueMixNote":"","deliverables":[]},' || chr(10) ||
            '  "backgroundMusicPlan": {"musicMood":"","bpmRange":"","instrumentation":"","energyCurve":"","duckingRule":"","usageNote":""},' || chr(10) ||
            '  "shootingSchedule": [],'
        ),
        '      "soundDesign": [' || chr(10) ||
        '        {"layerType":"ambient_bed","description":"","timingSeconds":0.0,"volumeLevel":"low"},' || chr(10) ||
        '        {"layerType":"sync_hit","description":"","timingSeconds":0.0,"volumeLevel":"high"}' || chr(10) ||
        '      ],',
        '      "soundDesign": [' || chr(10) ||
        '        {"layerType":"ambient_bed","description":"","timingSeconds":0.0,"volumeLevel":"low"},' || chr(10) ||
        '        {"layerType":"sync_hit","description":"","timingSeconds":0.0,"volumeLevel":"high"}' || chr(10) ||
        '      ],' || chr(10) ||
        '      "ambientBedDescription": "",' || chr(10) ||
        '      "syncHitDescription": "",' || chr(10) ||
        '      "backgroundMusicCue": {"cueType":"","musicMood":"","startTimeSeconds":0.0,"endTimeSeconds":0.0,"durationSeconds":0.0,"volumeLevel":"low_under_dialogue","duckUnderDialogue":true,"editNote":""},'
    ),
    metadata || jsonb_build_object('strictNoFallback', true, 'requiresAudioPlan', true)
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = 10
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    2,
    name || ' strict no-fallback',
    replace(
        template_body,
        '- ambientBedDescription is the continuous low bed. syncHitDescription is the punctuation hit.',
        '- ambientBedDescription is the continuous low bed. syncHitDescription is the punctuation hit.' || chr(10) ||
        '- Prefer shot.ambientBedDescription and shot.syncHitDescription when present.' || chr(10) ||
        '- Read shot.backgroundMusicCue and project backgroundMusicPlan so the panel label reflects the intended music cue when useful.'
    ),
    metadata || jsonb_build_object('strictNoFallback', true)
FROM creator_prompt_templates
WHERE template_key = 'STORYBOARD_TAG_GENERATE'
  AND version = 1
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    2,
    name || ' strict no-fallback',
    template_body || chr(10) ||
    'STRICT BACKEND VALIDATION: Return a complete LightingBuildSheetTag JSON object. The backend rejects incomplete JSON instead of filling fallback values.',
    metadata || jsonb_build_object('strictNoFallback', true)
FROM creator_prompt_templates
WHERE template_key = 'LIGHTING_BUILD_SHEET_TAG_GENERATE'
  AND version = 1
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    2,
    name || ' strict no-fallback',
    template_body || chr(10) ||
    'STRICT BACKEND VALIDATION: Return a complete CameraPlanSheetTag JSON object. The backend rejects incomplete JSON instead of filling fallback values.',
    metadata || jsonb_build_object('strictNoFallback', true)
FROM creator_prompt_templates
WHERE template_key = 'CAMERA_PLAN_SHEET_TAG_GENERATE'
  AND version = 1
ON CONFLICT (template_key, version) DO NOTHING;
