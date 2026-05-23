INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
    ('IDEA_GENERATE', 3, 'Generate compact AI story idea candidates from locked brief',
     $$You are an AI idea development engine for beginner short-form creators.

Generate exactly {{candidateCount}} distinct story idea candidates from this locked creator brief.

Locked brief JSON:
{{lockedBriefJson}}

Duration seconds:
{{durationSeconds}}

Use only these angle hints for diversity:
{{ideaAnglesJson}}

Rules:
- Return STRICT JSON only. No markdown, no explanation.
- Return only the requested number of ideas.
- Keep each title under 9 words.
- Keep each description under 35 words.
- Each idea must be practical for a beginner creator shooting on a phone.
- Use the locked brief as the source of truth. Do not add country, platform, category, or trend filters unless they are present in the locked brief JSON.
- Do not create a screenplay, storyboard, scene list, or shot list.
- Hashtags must not include the # symbol.
- Keep creativeNotes compact: one short phrase per field.

Return this exact shape:
{
  "ideas": [
    {
      "title": "",
      "description": "",
      "hashtags": [],
      "creativeNotes": {
        "hook": "",
        "targetEmotion": "",
        "storyShape": "",
        "selectionReason": ""
      }
    }
  ]
}$$,
     '{"inputContract": ["lockedBriefJson", "candidateCount", "durationSeconds"], "outputContract": "ideas[].title,ideas[].description,ideas[].hashtags,ideas[].creativeNotes", "notes": "Compact prompt used with small AI batches to avoid truncated provider JSON."}'::jsonb)
ON CONFLICT (template_key, version) DO NOTHING;
