INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    4,
    'Story script planner with dynamic category inference',
    replace(
        template_body,
        'Category: {{category}}',
        'Category Guidance: {{category}}
If Category Guidance is AI_INFER_FROM_IDEA, infer the most accurate category from the Idea and return that category in the JSON "category" field. Do not copy AI_INFER_FROM_IDEA into the output category.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = 3
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    7,
    'Screenplay shot planner with dynamic category inference',
    replace(
        template_body,
        'Category: {{category}}',
        'Category Guidance: {{category}}
If Category Guidance is AI_INFER_FROM_IDEA, infer the most accurate category from the Saved Story Script and Story Idea Memory and return that category in the JSON "category" field. Do not copy AI_INFER_FROM_IDEA into the output category.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = 6
ON CONFLICT (template_key, version) DO NOTHING;
