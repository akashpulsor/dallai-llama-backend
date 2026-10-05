-- PRE_PROD_STEP_CONTINUITY: the analysis half of pre-production's step-shot continuity engine
-- (pre-production-service service/continuity). One vision call per step: the attached image is
-- the previous shot; the variables are the new shot's text and every keyed prompt input. The model
-- only reports facts -- what the image establishes, which inputs conflict with it and how to restate
-- them, what the shot explicitly asks to change (with the exact words) -- and the service's
-- deterministic ContinuityResolver decides what wins. Generic on purpose: no field or value is
-- special-cased here; the field list comes from the caller.
INSERT INTO prompt_template (task_key, version, content, active) VALUES
('PRE_PROD_STEP_CONTINUITY', 1, $$You are a film continuity supervisor. The attached image is the PREVIOUS shot of a film. The NEXT shot will be made by editing that image, so everything the image already establishes must carry over unless the next shot explicitly asks for a change.

Visual fields to assess: {{fields}}

The previous shot showed: {{previousShot}}

THE NEXT SHOT
Description (the shot's own words): {{shotDescription}}
Screenplay scene: {{screenplay}}

Prompt inputs that will describe the next shot's environment. Each has a key, its source, and its value. Sources marked SHOT_METADATA, LIGHTING_PLAN and PROJECT_LOOK were written by generators that never saw the image -- treat them as suggestions, not as the story:
{{inputs}}

For every visual field that the image shows or that any input talks about:
1. "reference": what the IMAGE establishes for this field, concretely (e.g. for lighting: direction, colour temperature, practical sources, contrast). null if the image does not show it.
2. "transition": ONLY if the shot description or screenplay scene text explicitly requires this field to change from the previous shot (a new time, new weather, a new place...). Give the new value and "evidence": the exact words copied verbatim from that text. A value that appears only in the prompt inputs is NEVER a transition. Otherwise null.
3. "candidates": every input whose value bears on this field. "relation" is COMPATIBLE (agrees with the image), TENSION (differs but can coexist in one frame, nothing needs replacing) or CONFLICT (cannot hold together with what the image shows). For a CONFLICT, "compatibleRewrite" restates that input's useful intent so it fits the image -- keep the intent (energy, separation, crispness, mood), drop the physically contradictory part, and phrase it as instruction text that can replace the input in a prompt. Give a one-sentence "reason" a filmmaker would understand.

Also give:
- "changesForThisShot": short phrases for what the next shot itself introduces or changes (new subjects or objects, their action and placement, pose, expression). Not camera settings.
- "newElementLighting": one or two sentences on how the newly introduced elements must take the light already in the image -- which sources light them, what their surfaces reflect, where their shadows fall.
- "referenceSummary": one sentence describing the image.

Return STRICT JSON only, no markdown fences, no commentary:
{
  "referenceSummary": "...",
  "fields": [
    {
      "field": "one of the field keys above",
      "reference": "..." ,
      "transition": null,
      "candidates": [
        { "input": "an input key above", "relation": "COMPATIBLE | TENSION | CONFLICT", "reason": "...", "compatibleRewrite": "... or null" }
      ]
    }
  ],
  "changesForThisShot": ["..."],
  "newElementLighting": "..."
}$$, true)
ON CONFLICT DO NOTHING;
