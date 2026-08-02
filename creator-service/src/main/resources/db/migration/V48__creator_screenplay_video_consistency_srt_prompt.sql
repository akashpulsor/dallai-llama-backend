INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Screenplay planner with video continuity pacing and SRT contract',
    template_body || chr(10) || $screenplay$

SEEDANCE VIDEO GENERATION CONTRACT
- The screenplay will be used to generate 15 second Seedance scene clips and then merge them into the requested final duration.
- Maintain shot-to-shot consistency using a locked video bible: character identity, face, hair, wardrobe, body type, props, set geography, color palette, lighting temperature, lens language, screen direction, aspect ratio, and caption safe zones.
- Every scene or shot prompt must include enough continuity detail to survive independent video generation.
- Include adjacent-shot continuity notes so scene chat/regeneration changes only the requested scene while preserving the rest.
- Use reference-frame friendly language: mention approved storyboard frame, first generated frame, or previous clip endpoint as reference anchors when available.
- Add negative prompt constraints: no face drift, no wardrobe change, no random new actor, no changed room layout, no wrong aspect ratio, no unreadable text, no watermark, no random subtitles, no extra limbs.

PACING CONTRACT
- Decide videoPacingProfile.paceKey from the type of video:
  - fast_paced for comedy, meme, trend, fitness, high-retention explainers, countdowns, quick transformation, or shorts under 45 seconds.
  - slow_paced for emotional, romantic, documentary, reflective, dramatic, premium cinematic, or long-form moments.
  - balanced when the hook is quick but the story needs readable middle beats.
- For fast_paced, write shorter shot durations, punchy motion, bold caption beats, and tight visual payoffs.
- For slow_paced, write steadier camera movement, longer expressive beats, softer transitions, and captions with breathing room.

REQUIRED JSON ADDITIONS
- Root screenplay JSON must include:
  "videoPacingProfile": {"paceKey":"","averageShotSeconds":0,"cutDensity":"","captionRhythm":"","shotDurationRule":""},
  "seedancePromptStrategy": {"provider":"seedance","maxClipSeconds":15,"globalConsistencyPrompt":"","fastPacedPrompt":"","slowPacedPrompt":"","continuityTechniques":[],"srtRequired":true},
  "videoConsistencyBible": {"characterIdentityLocks":[],"wardrobeAndAppearanceLocks":[],"setAndPropLocks":[],"cameraLanguageLocks":[],"lightingAndColorLocks":[],"continuityRules":[],"negativePrompt":""},
  "srtCues": [],
  "srtFile": {"filename":"generated.srt","contentType":"application/x-subrip","cueCount":0,"durationSeconds":0,"content":""},
  "srt": ""
- Every shot JSON must include:
  "videoContinuity": {"previousShot":{},"nextShot":{},"lockedActors":"","lockedSideActors":"","lockedSet":"","lockedCamera":"","lockedLighting":"","globalNegativePrompt":""},
  "seedancePrompt": "",
  "pacingPrompt": "",
  "captionTrack": [],
  "srtCues": []
- captionTrack and srtCues must use absolute timeline seconds. SRT text must come from dialogue, voiceOver, or textOverlay and must not invent new spoken lines.
- The srtFile.content field must be a valid SubRip file body matching srtCues exactly.
$screenplay$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'seedanceVideoContractEnabled', true,
        'videoConsistencyBibleRequired', true,
        'srtFileRequired', true,
        'pacingProfileRequired', true,
        'inputContract.videoPacingProfile', 'object',
        'outputContract.srtFile', 'generated.srt'
    )
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;
