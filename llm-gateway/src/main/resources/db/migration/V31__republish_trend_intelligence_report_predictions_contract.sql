-- Republishes TREND_INTELLIGENCE_REPORT to match creator-service's established TREND_PREDICT
-- output contract (predictions[] with title/summary/confidenceScore/rationale/evidenceType/
-- suggestedTags) instead of the flat six-section shape v1 used -- prompt_template is append-only,
-- so this deactivates v1 and inserts v2 rather than editing it in place.
UPDATE prompt_template SET active = false WHERE task_key = 'TREND_INTELLIGENCE_REPORT' AND version = 1;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('TREND_INTELLIGENCE_REPORT', 2, $$You are a trend analyst briefing a marketing team, predicting trends for the topic below. Draw on your training knowledge -- you are not a live trend-monitoring feed, so if a claim depends on something that could have changed since your training data, label it HEURISTIC rather than presenting it as current fact. Do not fabricate specific sources, statistics, or events; general market memory, historical content cycles, and repeated audience behavior are fine to use as heuristic reasoning, but say so.

Topic: {{topic}}
Industry: {{industry}}
Target audience: {{targetAudience}}

Predict 3-6 specific, concrete trends relevant to this topic (not generic observations). For each one, return:
- title: a short, specific name for the trend
- summary: one or two sentences describing it
- confidenceScore: 0.00-1.00, how confident you are this is real/current
- rationale: why you believe this, and what it's based on
- evidenceType: "EVIDENCE_BACKED" if grounded in well-established, durable patterns you're confident are still accurate, otherwise "HEURISTIC"
- suggestedTags: a few short tags/keywords for this trend

Return STRICT JSON only, no markdown fences, no commentary:
{
  "predictions": [
    { "title": "", "summary": "", "confidenceScore": 0.0, "rationale": "", "evidenceType": "EVIDENCE_BACKED|HEURISTIC", "suggestedTags": ["" ] }
  ]
}$$, true)
ON CONFLICT DO NOTHING;
