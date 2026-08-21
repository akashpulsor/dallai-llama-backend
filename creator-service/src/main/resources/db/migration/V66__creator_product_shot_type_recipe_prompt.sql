INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
SELECT
    template_key,
    3,
    name || ' product shot type recipe',
    replace(
        replace(
            template_body,
            'TIER BEHAVIOR:',
            'PRODUCT SHOT TYPE & INGREDIENT RULES (only when the shot is product-led):' || chr(10) ||
            '- When shot.assignedProductShotType is present and non-empty, set productShotType to that exact value. Do not invent or substitute a different marketing shot type.' || chr(10) ||
            '- productShotType is the marketing/creative shot category (e.g. Hero Shot, Ingredient Shot, Pour Shot, Macro Shot, Pack Shot). It is unrelated to shotType/shotTypeFullName above, which are camera framing sizes (CU/MS/WS) and must keep their existing meaning - never copy one into the other.' || chr(10) ||
            '- Do not repeat the immediately preceding shot''s assigned productShotType or camera/lighting treatment; use the supplied assignment to keep each shot visually distinct from its neighbors.' || chr(10) ||
            '- If projectContext or the shot carries ingredientDetails or product evidence, weave the named ingredient(s)/material(s) into action, compositionSummary, or directorNote when relevant to this shot''s productShotType. Never invent an ingredient, material, certification, or claim not present in the supplied evidence.' || chr(10) ||
            '- When shot.assignedProductShotType is absent or blank, this shot is not product-led - leave productShotType as "".' || chr(10) || chr(10) ||
            'TIER BEHAVIOR:'
        ),
        '"directorNote": "", "creatorTip": "", "imageGenerationPromptOverride": ""',
        '"productShotType": "", "directorNote": "", "creatorTip": "", "imageGenerationPromptOverride": ""'
    ),
    metadata || jsonb_build_object('productShotTypeRecipe', true)
FROM creator_prompt_templates
WHERE template_key = 'STORYBOARD_TAG_GENERATE' AND version = 2
ON CONFLICT (template_key, version) DO NOTHING;
