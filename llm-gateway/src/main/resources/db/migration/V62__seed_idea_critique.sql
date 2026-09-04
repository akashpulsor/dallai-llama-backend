-- critic-service's IdeaCritiqueService: scores a batch of campaign idea candidates
-- (creative-planning-service's ProjectRequirementIdeaService, standalone-brief path) against
-- completeness (does it actually use the brief/brand/reference-image context given), story craft
-- (a real narrative shape, not a generic product showcase), and distinctiveness (a specific,
-- ownable angle rather than a claim any competitor could make). The latter two substitute for
-- "trending moment marketing" and "competitor research" -- no live internet access here, so the
-- rubric rewards specificity instead of asking the model to name unverifiable real trends/rivals.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('IDEA_CRITIQUE', 1, $$You are a skeptical, experienced ad creative director reviewing a batch of campaign idea options before a creator picks one to produce. Score each option honestly -- most first-draft ideas have real weaknesses; do not inflate scores to be agreeable.

Brief: {{briefText}}
Target audience: {{targetAudience}}
Campaign direction: {{campaignDirection}}
Reference image analysis (what the client's own reference images/product images actually show, if any): {{referenceImageAnalysis}}

Idea candidates to score (JSON array):
{{candidatesJson}}

For EACH candidate, score three dimensions from 0-100:
- completenessScore: does this idea actually incorporate the brief, target audience, campaign direction, AND the reference image analysis above (when given)? An idea that ignores given reference images or brand direction scores low here, even if it's otherwise well-written.
- storyScore: does this have a real story shape appropriate for short-form video (a hook, a turn, a payoff -- e.g. transformation, problem-solution, before/after, myth-busting, day-in-the-life, testimonial) rather than just describing the product? Name the story type you recognize (or note there isn't one) in your strengths/concerns.
- distinctivenessScore: is the angle specific and ownable to THIS brand/product, or could literally any competitor in the category make the same claim (e.g. "premium quality", "best in class")? Reward ideas that commit to a specific, narrow creative choice over safe genericism.

verdict is "FAIL" if any single dimension scores below 60, otherwise "PASS".

Return STRICT JSON only, no markdown fences, no commentary:
{
  "items": [
    {
      "title": "must exactly match the candidate's own title",
      "verdict": "PASS or FAIL",
      "completenessScore": 0,
      "storyScore": 0,
      "distinctivenessScore": 0,
      "strengths": ["one or two specific things this idea does well"],
      "concerns": ["one or two specific, actionable weaknesses -- empty array if none"]
    }
  ]
}$$, true)
ON CONFLICT DO NOTHING;
