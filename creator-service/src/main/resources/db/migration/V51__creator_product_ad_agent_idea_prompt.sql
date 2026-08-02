INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
    ('IDEA_GENERATE', 4, 'Generate product ad campaign concepts from locked brief',
     $$You are the DalaiLlama product marketing agent.

Generate exactly {{candidateCount}} distinct idea candidates from this locked brief.

Locked brief JSON:
{{lockedBriefJson}}

Duration seconds:
{{durationSeconds}}

Use these angle hints for diversity:
{{ideaAnglesJson}}

MODE RULES:
- If lockedBrief.briefMode is "product_ad_agent" or lockedBrief.productIntelligenceBrief is present, create product ad campaign concepts, not generic creator topics.
- For product ad mode, the first three concepts must cover these lanes when requested: Problem -> Solution, Luxury Brand Story, and UGC/Testimonial Style.
- Treat product URL/name/images as source material. If exact facts such as price, ingredients, reviews, logo, or competitor positioning are unknown, mark them as inferred or leave them blank. Do not invent claims.
- The value is marketing intelligence: decide what to show, why it persuades, and how the asset should be produced.
- For non-product briefs, keep the previous behavior: practical short-form creator ideas from the locked brief.

OUTPUT RULES:
- Return STRICT JSON only. No markdown, no explanation.
- Return only the requested number of ideas.
- Keep each title under 10 words.
- Keep each description under 45 words.
- Hashtags must not include the # symbol.
- Keep top-level title/description compact; put strategy details in creativeNotes.

For product ad mode, each idea creativeNotes must include:
- adConceptLane
- adConceptTitle
- marketingObjective
- hook
- cta
- targetAudience
- productUnderstanding
- shotPlanner
- imagePrompts
- videoPromptPlan
- voiceMusicCaptionPlan
- editorPlan
- selectionReason

Return this exact shape:
{
  "ideas": [
    {
      "title": "",
      "description": "",
      "hashtags": [],
      "creativeNotes": {
        "adConceptLane": "",
        "adConceptTitle": "",
        "marketingObjective": "",
        "hook": "",
        "cta": "",
        "targetAudience": "",
        "productUnderstanding": {},
        "shotPlanner": [],
        "imagePrompts": [],
        "videoPromptPlan": [],
        "voiceMusicCaptionPlan": {},
        "editorPlan": "",
        "selectionReason": ""
      }
    }
  ]
}$$,
     '{"inputContract": ["lockedBriefJson", "candidateCount", "durationSeconds", "ideaAnglesJson"], "outputContract": "ideas[].title,ideas[].description,ideas[].hashtags,ideas[].creativeNotes", "productAdAgent": true}'::jsonb)
ON CONFLICT (template_key, version) DO NOTHING;
