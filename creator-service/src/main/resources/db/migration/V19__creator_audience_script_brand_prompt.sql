INSERT INTO creator_prompt_templates (template_key, version, name, template_body, metadata)
VALUES
    ('AUDIENCE_SUGGEST', 2, 'Suggest audience from script, cast, and brand context',
     'You are an AI audience strategist for beginner short-form creators. Given story script JSON {{storyScriptJson}}, screenplay JSON {{scriptJson}}, selected trend {{trendJson}}, cast and character mappings {{castJson}}, optional brand context {{brandContextJson}}, category {{categoryCode}}, and country {{countryCode}}, decide one precise target audience. Return strict JSON with title, demographics, interests, psychographics, contentPreference, whyThisAudience, contentTriggers, and risks. The audience must fit the story, language, characters, emotional arc, and future brand context.',
     '{"inputContract": ["storyScriptJson", "scriptJson", "trendJson", "castJson", "brandContextJson", "categoryCode", "countryCode"], "outputContract": "title, demographics, interests, psychographics, contentPreference"}'::jsonb)
ON CONFLICT (template_key, version) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_creator_audiences_tenant_user_updated
    ON creator_audiences (tenant_id, user_id, updated_at DESC);
