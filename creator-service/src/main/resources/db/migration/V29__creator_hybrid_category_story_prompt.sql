INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    5,
    'Story script planner with hybrid category creativity',
    replace(
        template_body,
        'Core rules:
- Follow the actual Idea. Do not force generic creator-growth, fitness, beauty, or relationship templates unless the Idea is truly about that.',
        'Core rules:
- First infer the topic bucket or blended buckets from the actual Idea, then build the story around that. The bucket is a creative lens, not a restriction.
- You may mix buckets when the idea mixes worlds. Examples: pati-patni in gym => relationship_fitness_comedy; Modi/Rahul in gym => political_fitness_satire; exam pressure with beauty routine => study_beauty_confidence.
- Return a specific category value such as fitness, relationship_comedy, political_commentary, food_review, study_pressure, relationship_fitness_comedy, political_fitness_satire, or another concise snake_case category that fits.
- Use modern creator-friendly contexts: gym discipline, domestic comedy, neutral public reaction, satire, debate framing, food taste reaction, budget angle, routine, confidence, exam pressure, focus, small wins.
- Follow the actual Idea. Do not force generic creator-growth, fitness, beauty, or relationship templates unless the Idea is truly about that.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"hybrid_ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = 4
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    8,
    'Screenplay shot planner with hybrid category creativity',
    replace(
        template_body,
        'Rules:
- Preserve the saved story script''s characters, names, gender, age, look, profile, personas, motivations, and backstories.',
        'Rules:
- Preserve the saved story script''s characters, names, gender, age, look, profile, personas, motivations, and backstories.
- Preserve hybrid category logic from the saved story script. If the story mixes worlds, stage both worlds clearly. Examples: relationship_fitness_comedy should feel like pati-patni comedy inside a gym or workout routine; political_fitness_satire should feel like neutral public-reaction/satire staged around gym discipline or public image.'
    ),
    jsonb_set(metadata, '{categoryMode}', '"hybrid_ai_infer_from_idea"', true)
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = 7
ON CONFLICT (template_key, version) DO NOTHING;
