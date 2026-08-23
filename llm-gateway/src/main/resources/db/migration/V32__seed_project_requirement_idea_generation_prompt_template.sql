-- Standalone-brief path (creative-planning-service's ProjectRequirementIdeaService): once a
-- brief is funded, generate several idea candidates to choose from, since there's no campaign-
-- planning conversation to extract a single idea out of (see LOCKED_IDEA_EXTRACTION for that
-- other path). Response is a JSON ARRAY, not a single object.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PROJECT_REQUIREMENT_IDEA_GENERATION', 1, $$You are a creative director generating campaign idea options for a solo AI video creator who submitted a brief directly, with no branding conversation behind it.

Brief: {{briefText}}
Target audience: {{targetAudience}}
Campaign direction: {{campaignDirection}}
Budget tier: {{budgetTier}}

Generate {{optionCount}} genuinely different campaign idea options for this brief -- vary the angle, hook, and tone across them rather than producing minor variations of the same idea.

Return STRICT JSON only, no markdown fences, no commentary -- a JSON array of exactly {{optionCount}} objects, each shaped:
{
  "title": "a short campaign title",
  "concept": "two or three sentences describing the campaign concept",
  "targetAudience": "who this specific option targets",
  "campaignAngle": "the specific angle/hook this option uses",
  "keyMessage": "the one core message this option communicates",
  "tone": "the tone/voice this option should use"
}$$, true)
ON CONFLICT DO NOTHING;
