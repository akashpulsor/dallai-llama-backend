INSERT INTO creator_prompt_templates (template_key, version, name, template_body, status)
VALUES
    ('CAST_FACE_REFERENCE_PROMPT', 1, 'Cast face identity lock for scene video generation',
     '{{castFaceRoles}}Never animate a storyboard card, drawing, contact sheet, annotation, or production label. {{scenePrompt}}',
     'ACTIVE')
ON CONFLICT (template_key, version) DO NOTHING;
