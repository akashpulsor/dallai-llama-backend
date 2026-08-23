-- Restores creator-service's real HOOK_BEAT_PLAN_GENERATE pattern: plan the hook and beat
-- structure BEFORE writing prose, so the script writer has a real structure to follow instead of
-- inventing pacing on the fly.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_HOOK_BEAT_PLAN_GENERATE', 1, $$You are a showrunner planning the hook and beat structure for a {{durationSeconds}}-second video, BEFORE any script prose gets written.

Idea/brief:
{{brief}}

Plan the structure only -- do not write prose or dialogue yet. First, a hook line: what the first 2-3 seconds actually say or show to earn attention. Then an ordered list of beats, each with: a short title, its narrative purpose, its emotional target, how it escalates from the previous beat, and (only for the final beat) its payoff.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "hookLine": "",
  "beats": [
    { "title": "", "purpose": "", "emotionalTarget": "", "escalationFromPrevious": "", "payoff": "" }
  ]
}$$, true)
ON CONFLICT DO NOTHING;
