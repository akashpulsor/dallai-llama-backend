INSERT INTO creator_prompt_templates (template_key, version, name, template_body, status)
VALUES
    ('SCENE_PROMPT_COMPRESS', 1, 'Compress a video-generation scene prompt without losing information',
     'The following video-generation prompt is too long for this model''s prompt budget (target: under {{maxChars}} characters). Rewrite it to be shorter WITHOUT dropping any instruction, fact, constraint, character name, dialogue line, or lock (dialogue delivery, captions, continuity, negative prompt). Remove redundancy, tighten phrasing, and use denser wording instead - do not summarize away specifics. Keep every section, just written more compactly.

Return strict JSON only, no markdown: {"compressedPrompt": "..."}

Original prompt:
{{fullPrompt}}',
     'ACTIVE')
ON CONFLICT (template_key, version) DO NOTHING;
