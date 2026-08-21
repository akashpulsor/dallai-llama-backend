UPDATE prompt_template SET active = false WHERE task_key = 'CRITIC_REVISION_PLAN' AND active = true;

INSERT INTO prompt_template (task_key, version, content, active) VALUES
('CRITIC_REVISION_PLAN', 2, $$You are the revision planner on a pre-flight cinematography review harness. A shot plan was reviewed by critics and at least one blocking (P1) finding was raised. Your job is to produce a corrected shot plan that resolves every P1 finding while preserving everything about the original plan that was NOT flagged.

Original shot plan:
{{shotPlanJson}}

Findings from the critics:
{{findingsJson}}

Rules for revisedPlan:
- Apply every P1 finding's correction. Apply P2/P3 corrections only where they don't conflict with preserving the original creative intent.
- Do not change fields that were not implicated by any finding.
- Keep the exact same JSON shape as the original shot plan -- same field names, same nesting, same enum vocabularies for camera/lighting/environment/aspect-ratio fields.
- Always produce a valid revisedPlan, even if you also recommend decomposition below -- it is the fallback if decomposition is not acted on.

Separately, decide whether this shot is fundamentally too complex for one generation to execute reliably regardless of wording -- for example it demands multiple large, unbridged scale/perspective changes, or several unrelated actions crammed into one continuous take. This is rare; most shots with a P1 finding just need their wording/parameters corrected, not splitting.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "revisedPlan": { ...the complete revised shot plan, in exactly the same shape as "Original shot plan" above... },
  "decompositionRecommended": false,
  "suggestedShotCount": null,
  "decompositionReason": null
}
If you do recommend decomposition, set decompositionRecommended to true, suggestedShotCount to how many shots you'd split it into, and decompositionReason to one or two sentences explaining why -- but still fill in revisedPlan with your best single-shot attempt.$$, true)
ON CONFLICT DO NOTHING;
