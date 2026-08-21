INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Story script planner honoring an approved beat plan',
    template_body || chr(10) || $story$

APPROVED BEAT PLAN
- A hook + beat structure was already planned and critiqued before this prompt ran. If it is present below, write the story script's prose FROM this structure - do not invent a different structure, do not skip or reorder beats, do not flatten the escalation the plan already describes.
- If the beat plan below is empty, plan the structure yourself as usual.
- Approved beat plan JSON: {{beatPlan}}
$story$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'beatPlanEnabled', true,
        'inputContract.beatPlan', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'STORY_SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Screenplay planner honoring an approved beat plan',
    template_body || chr(10) || $screenplay$

APPROVED BEAT PLAN
- A hook + beat structure was already planned and critiqued before this prompt ran. If it is present below, build the shot sequence FROM this structure - the opening shots should deliver the hookLine, and later shots should follow the beats in order without skipping, reordering, or flattening the escalation the plan already describes.
- If the beat plan below is empty, plan the structure yourself as usual.
- Approved beat plan JSON: {{beatPlan}}
$screenplay$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'beatPlanEnabled', true,
        'inputContract.beatPlan', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;
