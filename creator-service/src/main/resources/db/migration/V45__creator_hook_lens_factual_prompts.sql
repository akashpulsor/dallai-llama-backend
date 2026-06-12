INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Story script planner with factual hook lens',
    template_body || chr(10) || $story$

OPENING HOOK LENS INPUT
- Hook Lens: {{hookLens}}
- Hook Lens Guidance: {{hookLensGuidance}}

FACTUAL BRIDGE RULES
- If hookLens is direct, start directly with the original story, conflict, or premise.
- If hookLens is history, geography, philosophy, science, culture, psychology, or economics, use the lens only when a truthful, reliable bridge exists.
- Use only reliable, commonly known facts or clearly framed analogies. Do not invent dates, places, people, events, causes, quotes, statistics, or links.
- Do not turn a loose similarity into a factual causal claim. If the relation is only metaphorical, say it as an analogy.
- If no accurate relation exists, do not force a bridge. Use a direct hook and set hookBridge.relationConfidence to "none".

REQUIRED JSON ADDITIONS
- Root story JSON must include "hookLens", "hookLensGuidance", "hookBridge", and "factualityNotes".
- hookBridge must include: "hookLens", "factualHook", "bridgeLine", "relationConfidence", and "noFalseLinkReason".
- factualityNotes must include: "verifiedFacts", "avoidedClaims", and "requiresHumanFactCheck".
- verifiedFacts must list only facts used in the hook or bridge. If none are used, return an empty array.
$story$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'hookLensEnabled', true,
        'strictFactualBridge', true,
        'inputContract.hookLens', 'string',
        'inputContract.hookLensGuidance', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'STORY_SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'STORY_SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    version + 1,
    'Screenplay planner with factual hook lens',
    template_body || chr(10) || $screenplay$

OPENING HOOK LENS INPUT
- Hook Lens: {{hookLens}}
- Hook Lens Guidance: {{hookLensGuidance}}

FACTUAL BRIDGE RULES
- If hookLens is direct, open the screenplay directly from the original story.
- If hookLens is history, geography, philosophy, science, culture, psychology, or economics, use the lens only when the bridge is truthful and useful for the original story.
- Use only reliable, commonly known facts or clearly framed analogies. Do not invent dates, places, people, events, causes, quotes, statistics, or links.
- Do not imply a real-world historical, geographic, scientific, or philosophical connection unless the story actually supports it.
- If no accurate relation exists, do not force a bridge. Use a direct hook and set hookBridge.relationConfidence to "none".

REQUIRED JSON ADDITIONS
- Root screenplay JSON must include:
  "hookLens": "",
  "hookLensGuidance": {},
  "hookBridge": {"hookLens":"","factualHook":"","bridgeLine":"","relationConfidence":"","noFalseLinkReason":""},
  "factualityNotes": {"verifiedFacts":[],"avoidedClaims":[],"requiresHumanFactCheck":false}
- The first shots should honor the hookBridge when relationConfidence is not "none".
- If relationConfidence is "none", do not reference an external fact in the opening shots.
$screenplay$,
    coalesce(metadata, '{}'::jsonb) || jsonb_build_object(
        'hookLensEnabled', true,
        'strictFactualBridge', true,
        'inputContract.hookLens', 'string',
        'inputContract.hookLensGuidance', 'object'
    )
FROM creator_prompt_templates
WHERE template_key = 'SCRIPT_GENERATE'
  AND version = (SELECT max(version) FROM creator_prompt_templates WHERE template_key = 'SCRIPT_GENERATE')
ON CONFLICT (template_key, version) DO NOTHING;
