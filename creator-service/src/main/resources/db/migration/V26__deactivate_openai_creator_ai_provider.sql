UPDATE creator_ai_providers
SET active = false,
    visible = false,
    default_provider = false,
    updated_at = now()
WHERE code = 'openai';

UPDATE creator_ai_providers
SET active = true,
    visible = true,
    default_provider = true,
    sort_order = 10,
    updated_at = now()
WHERE code = 'gemini';

UPDATE creator_ai_providers
SET default_provider = false,
    updated_at = now()
WHERE code <> 'gemini'
  AND default_provider = true;
