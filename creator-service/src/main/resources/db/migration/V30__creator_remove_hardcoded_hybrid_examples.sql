INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    6,
    'Story script planner with open-ended category inference',
    replace(
        replace(
            replace(
                template_body,
                '- You may mix buckets when the idea mixes worlds. Examples: pati-patni in gym => relationship_fitness_comedy; Modi/Rahul in gym => political_fitness_satire; exam pressure with beauty routine => study_beauty_confidence.',
                '- You may mix buckets when the idea mixes worlds. Let the written topic decide the blend, setting, conflict, and comedic or emotional angle.'
            ),
            '- Return a specific category value such as fitness, relationship_comedy, political_commentary, food_review, study_pressure, relationship_fitness_comedy, political_fitness_satire, or another concise snake_case category that fits.',
            '- Return a concise snake_case category value that the model infers from the idea. Combine descriptors only when the idea naturally blends domains.'
        ),
        '- Use modern creator-friendly contexts: gym discipline, domestic comedy, neutral public reaction, satire, debate framing, food taste reaction, budget angle, routine, confidence, exam pressure, focus, small wins.',
        '- Use a modern creator-friendly context derived from the topic itself. Do not select from a fixed list of categories or scenarios.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"open_ended_ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = 5
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    9,
    'Screenplay shot planner with open-ended category inference',
    replace(
        template_body,
        '- Preserve hybrid category logic from the saved story script. If the story mixes worlds, stage both worlds clearly. Examples: relationship_fitness_comedy should feel like pati-patni comedy inside a gym or workout routine; political_fitness_satire should feel like neutral public-reaction/satire staged around gym discipline or public image.',
        '- Preserve the saved story script category logic. If the story mixes worlds, stage the blend clearly using the characters, topic, conflict, setting, and category returned by the story script. Do not rely on fixed example scenarios.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"open_ended_ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = 8
ON CONFLICT (template_key, version) DO NOTHING;
