INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    7,
    'Story script planner with storytelling type',
    template_body || chr(10) || $story$

STORYTELLING TYPE INPUT
- Storytelling Type: {{storytellingType}}
- Storytelling Guidance: {{storytellingGuidance}}

STORYTELLING RULES
- Honor storytellingType as the main format choice for the story layer.
- If storytellingType is narrator_visual_mix, write simple narration with engaging dialogue and plan beats that alternate narrator or creator face moments with related visual moments.
- Related visual moments are not mandatory acted drama. They can be recordable B-roll, maps, objects, reactions, places, graphics, or generated visuals.
- If storytellingType is talking_head_explainer, keep most beats narrator-led with only a few visual inserts.
- If storytellingType is visual_voiceover, let voice over carry most of the story and keep on-camera dialogue minimal.
- If storytellingType is dialogue_scene or dramatic_scene, write natural acted dialogue, but still keep the short phone-friendly.

REQUIRED JSON ADDITIONS
- Root story JSON must include "storytellingType" and "storytellingGuidance".
- Beats may describe whether the beat is narrator-face, related visual, or acted dialogue when useful.
$story$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'storytellingTypeEnabled', true,
        'inputContract.storytellingType', 'string',
        'inputContract.storytellingGuidance', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'STORY_SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    12,
    'Screenplay planner with storytelling type and visual asset roles',
    template_body || chr(10) || $screenplay$

STORYTELLING TYPE INPUT
- Storytelling Type: {{storytellingType}}
- Storytelling Guidance: {{storytellingGuidance}}

STORYTELLING RULES
- Honor storytellingType as the screenplay format.
- If storytellingType is narrator_visual_mix, alternate narrator_face shots with related_visual shots. A related_visual shot may be recorded by the user or generated later.
- If storytellingType is narrator_visual_mix, do not force every shot to be drama or acted scene. Use simple narration, engaging dialogue, and visual inserts related to the story.
- If storytellingType is talking_head_explainer, keep most shots as narrator_face and use related_visual inserts only where they clarify the point.
- If storytellingType is visual_voiceover, most shots should be related_visual. Use voiceOver and textOverlay for narration; dialogue can be empty on visual shots.
- If storytellingType is dialogue_scene or dramatic_scene, acted_dialogue shots are allowed, but keep dialogue natural and concise.

REQUIRED JSON ADDITIONS
- Root screenplay JSON must include:
  "storytellingType": "",
  "storytellingGuidance": {},
  "shotMixPlan": {"narratorFacePercent":0,"relatedVisualPercent":0,"recordOrGenerateVisualsNote":""}
- Every shot JSON must include:
  "storytellingRole": "narrator_face | related_visual | acted_dialogue",
  "assetCaptureMode": "record | generate | record_or_generate",
  "assetGenerationPrompt": ""
- For related_visual shots, dialogue may be {}, but voiceOver, textOverlay, action, sketchPrompt, and assetGenerationPrompt must carry the beat clearly.
$screenplay$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'storytellingTypeEnabled', true,
        'requiresShotAssetRoles', true,
        'inputContract.storytellingType', 'string',
        'inputContract.storytellingGuidance', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;
