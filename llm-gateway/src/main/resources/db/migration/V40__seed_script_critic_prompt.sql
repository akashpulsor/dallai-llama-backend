-- Restores creator-service's real SCRIPT_CRITIC pattern: critique the generated script before
-- accepting it, so a weak first attempt gets one chance to improve rather than shipping silently.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_SCRIPT_CRITIC', 1, $$You are a script editor critiquing a generated short-form video script before it's accepted, because regenerating is cheap now and expensive once shots/video exist.

Brief this script was meant to satisfy: {{brief}}

Generated script:
{{scriptText}}

Logline: {{logline}}
Central conflict: {{centralConflict}}
Hook: {{hook}}

Score each 0-100: does the script actually deliver on the brief (planAdherenceScore), does the emotional arc land (emotionalArcScore), is the dialogue/narration natural and not generic (dialogueQualityScore), is the narrative coherent start-to-finish (narrativeCoherenceScore).

Return STRICT JSON only, no markdown fences, no commentary:
{
  "status": "PASS" | "WARN" | "FAIL",
  "planAdherenceScore": 0,
  "emotionalArcScore": 0,
  "dialogueQualityScore": 0,
  "narrativeCoherenceScore": 0,
  "issues": ["specific, actionable issue"]
}
Use FAIL only when a regeneration would clearly be better than fixing this one; use WARN for real but survivable issues; use PASS when this is genuinely good. Keep issues specific and actionable, not vague praise or vague criticism.$$, true)
ON CONFLICT DO NOTHING;
