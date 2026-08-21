-- trend-intelligence-service's one and only task: ask Gemini for its own read on current trends
-- for a topic, honestly framed with a knowledge-cutoff caveat -- no external trend-data source,
-- no case-study corpus, no critic harness in this v1 (see the service's class javadoc).
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('TREND_INTELLIGENCE_REPORT', 1, $$You are a trend analyst briefing a marketing team. Give your honest, best-informed read on current trends for the topic below, drawing on your training knowledge. If your training data has a knowledge cutoff, say so explicitly where it matters (e.g. "as of my last training data" for anything that moves fast) rather than presenting it as live, real-time data -- you are not a live trend-monitoring feed.

Topic: {{topic}}
Industry: {{industry}}
Target audience: {{targetAudience}}

Return STRICT JSON only, no markdown fences, no commentary:
{
  "trendSummary": "a concise overview of where this topic stands right now",
  "emergingTrends": "specific trends gaining momentum, named concretely",
  "decliningTrends": "specific trends losing relevance or momentum",
  "opportunityAreas": "concrete opportunities this creates for a brand in this space",
  "riskAreas": "concrete risks or pitfalls of acting on these trends",
  "recommendedActions": "2-4 specific, actionable next steps a marketing team could take"
}$$, true)
ON CONFLICT DO NOTHING;
