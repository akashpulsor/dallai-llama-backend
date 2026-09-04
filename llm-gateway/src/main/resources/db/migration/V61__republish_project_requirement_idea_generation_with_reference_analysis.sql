-- Republishes PROJECT_REQUIREMENT_IDEA_GENERATION as version 2 -- adds an optional
-- {{referenceImageAnalysis}} block summarizing the vision analysis of any product/project
-- reference images uploaded with the brief (see ReferenceImageAnalysisService and
-- ProjectReferenceImageAnalysisService, both deferred until the requirement is funded), so
-- generated ideas can actually reflect the camera work, mood, and emotion those images show
-- instead of only the brief's own text. prompt_template is append-only (see V34's precedent:
-- deactivate old, insert new).
UPDATE prompt_template SET active = false WHERE task_key = 'PROJECT_REQUIREMENT_IDEA_GENERATION' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PROJECT_REQUIREMENT_IDEA_GENERATION', 2, $$You are a creative director generating campaign idea options for a solo AI video creator who submitted a brief directly, with no branding conversation behind it.

Brief: {{briefText}}
Target audience: {{targetAudience}}
Campaign direction: {{campaignDirection}}
Budget tier: {{budgetTier}}
Reference image analysis: {{referenceImageAnalysis}}

Generate {{optionCount}} genuinely different campaign idea options for this brief -- vary the angle, hook, and tone across them rather than producing minor variations of the same idea. When reference image analysis is given, ground your options in what those images actually show (camera work, lighting/mood, emotion, subject matter) rather than ignoring it.

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
