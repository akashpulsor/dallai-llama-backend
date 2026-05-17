INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
    ('TREND_INSIGHT', 1, 'Explain why selected trend worked and posting windows',
     'Explain why this selected short-form trend worked and recommend best posting windows. Trend: {{trendJson}}. Country: {{countryCode}}. Timezone: {{timezone}}. Use stored score, velocity, tags, summary, source payload, and prediction payload. Return compact JSON with summary, whyItWorked[], bestTimes[] with label/window/timezone/reason, creatorActions[], confidenceScore, evidenceType. If evidence is weak, label reasoning as heuristic.',
     '{"inputContract": ["trendJson", "countryCode", "timezone"], "outputContract": "summary, whyItWorked[], bestTimes[], creatorActions[]"}'::jsonb)
ON CONFLICT (template_key, version) DO NOTHING;
