ALTER TABLE creator_categories
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS icon_key VARCHAR(80),
    ADD COLUMN IF NOT EXISTS sort_order INTEGER NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS visible BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS prompt_context TEXT;

CREATE TABLE IF NOT EXISTS creator_category_keywords (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES creator_categories(id) ON DELETE CASCADE,
    source_type VARCHAR(80) NOT NULL,
    locale VARCHAR(24) NOT NULL DEFAULT 'en-IN',
    include_terms JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_terms JSONB NOT NULL DEFAULT '[]'::jsonb,
    weight NUMERIC(5, 2) NOT NULL DEFAULT 1.00,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_category_keyword UNIQUE (category_id, source_type, locale)
);

CREATE TABLE IF NOT EXISTS creator_trend_category_map (
    trend_id UUID NOT NULL REFERENCES creator_trends(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES creator_categories(id) ON DELETE CASCADE,
    confidence_score NUMERIC(5, 2) NOT NULL DEFAULT 1.00,
    mapped_by VARCHAR(48) NOT NULL DEFAULT 'scheduler',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (trend_id, category_id)
);

CREATE INDEX IF NOT EXISTS idx_creator_categories_visible
    ON creator_categories (active, visible, sort_order);

CREATE INDEX IF NOT EXISTS idx_creator_category_keywords_scheduler
    ON creator_category_keywords (active, source_type, locale);

CREATE INDEX IF NOT EXISTS idx_creator_trend_category_map_category
    ON creator_trend_category_map (category_id, confidence_score DESC);

UPDATE creator_categories
SET active = false,
    visible = false,
    updated_at = now()
WHERE code NOT IN (
    'fitness',
    'beauty',
    'food',
    'study',
    'fashion',
    'tech_ai',
    'finance_crypto',
    'business_startup',
    'entertainment',
    'travel',
    'lifestyle_home',
    'self_improvement'
);

INSERT INTO creator_categories (
    code,
    display_name,
    description,
    icon_key,
    sort_order,
    active,
    visible,
    prompt_context,
    status,
    metadata
)
VALUES
    ('fitness', 'Fitness', 'Workout, gym, sports nutrition, wellness routines, body transformation, and creator-friendly fitness challenges.', 'dumbbell', 10, true, true, 'Prioritize achievable routines, visual transformation hooks, beginner-friendly execution, and safety-aware fitness advice.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('beauty', 'Beauty', 'Skin care, makeup, hair care, grooming, glow-up routines, product-free tips, and beauty transformations.', 'sparkles', 20, true, true, 'Prioritize visual before/after beats, realistic routines, skin-safe language, and high-save tutorial formats.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('food', 'Food', 'Recipes, meal prep, street food, high-protein meals, budget food, kitchen hacks, and regional food moments.', 'utensils', 30, true, true, 'Prioritize appetizing visual steps, quick recipe structure, regional relevance, sensory details, and simple execution.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('study', 'Study', 'Student routines, exams, productivity, note-taking, desk setup, focus systems, and learning motivation.', 'book-open', 40, true, true, 'Prioritize relatable student pain points, exam urgency, repeatable routines, and calm motivation.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('fashion', 'Fashion', 'Outfits, styling, wardrobe basics, affordable fashion, seasonal looks, and occasion-based styling.', 'shirt', 50, true, true, 'Prioritize outfit reveals, comparison beats, practical styling logic, and strong first-frame visual identity.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('tech_ai', 'Tech & AI', 'AI tools, gadgets, apps, automation, creator workflows, software tips, and emerging technology.', 'bot', 60, true, true, 'Prioritize tool usefulness, demo-first hooks, practical workflows, and clear non-hype explanations.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('finance_crypto', 'Finance & Crypto', 'Personal finance, saving, investing basics, market education, crypto awareness, and money habits.', 'wallet-cards', 70, true, true, 'Prioritize educational framing, risk-aware language, no financial guarantees, and simple examples.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('business_startup', 'Business & Startups', 'Entrepreneurship, side hustles, SaaS, marketing, creator business, sales, and startup lessons.', 'briefcase-business', 80, true, true, 'Prioritize concrete business insight, founder relatability, tactical examples, and credibility-building structure.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('entertainment', 'Entertainment', 'Movies, shows, music, celebrities, memes, pop culture, comedy formats, and reaction trends.', 'clapperboard', 90, true, true, 'Prioritize fast recognition, culturally relevant setup, reaction beats, and shareable punchlines.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('travel', 'Travel', 'Destinations, itineraries, budget travel, local experiences, packing, hotels, and hidden spots.', 'map', 100, true, true, 'Prioritize place clarity, sensory detail, itinerary usefulness, budget context, and aspirational but inspectable visuals.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('lifestyle_home', 'Lifestyle & Home', 'Home organization, daily routines, decor, cleaning, family life, habits, and cozy living.', 'home', 110, true, true, 'Prioritize relatable everyday problems, visual order, simple routines, and save-worthy home improvements.', 'ACTIVE', '{"mvp": true}'::jsonb),
    ('self_improvement', 'Self Improvement', 'Motivation, discipline, mental models, confidence, habits, journaling, and personal growth.', 'trending-up', 120, true, true, 'Prioritize emotionally honest hooks, specific behavior changes, reflective pacing, and non-preachy motivation.', 'ACTIVE', '{"mvp": true}'::jsonb)
ON CONFLICT (code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    visible = EXCLUDED.visible,
    prompt_context = EXCLUDED.prompt_context,
    status = EXCLUDED.status,
    metadata = creator_categories.metadata || EXCLUDED.metadata,
    updated_at = now();

WITH keyword_seed(category_code, source_type, locale, include_terms, exclude_terms, weight) AS (
    VALUES
        ('fitness', 'google_trends', 'en-IN', '["gym motivation","home workout","weight loss","fitness challenge","protein meal","body transformation"]'::jsonb, '["steroid","unsafe diet","extreme cut"]'::jsonb, 1.00),
        ('fitness', 'reddit', 'en-IN', '["fitness","homegym","workout","bodyweightfitness","nutrition"]'::jsonb, '["nsfw","injury gore"]'::jsonb, 0.90),
        ('fitness', 'youtube_public_pages', 'en-IN', '["workout shorts","gym reels","fitness transformation","beginner workout"]'::jsonb, '["dangerous challenge"]'::jsonb, 0.85),

        ('beauty', 'google_trends', 'en-IN', '["skin care","glow up","makeup tutorial","hair care","beauty routine","glass skin"]'::jsonb, '["medical cure","skin bleaching"]'::jsonb, 1.00),
        ('beauty', 'reddit', 'en-IN', '["skincareaddiction","makeupaddiction","haircare","beauty"]'::jsonb, '["nsfw","diagnosis"]'::jsonb, 0.90),
        ('beauty', 'youtube_public_pages', 'en-IN', '["makeup shorts","skin care routine","beauty hacks","hair transformation"]'::jsonb, '["unsafe skin"]'::jsonb, 0.85),

        ('food', 'google_trends', 'en-IN', '["easy recipe","high protein meal","street food","meal prep","healthy breakfast","budget recipe"]'::jsonb, '["food poisoning","unsafe raw"]'::jsonb, 1.00),
        ('food', 'reddit', 'en-IN', '["recipes","indianfood","mealprep","streetfood","foodhacks"]'::jsonb, '["nsfw"]'::jsonb, 0.90),
        ('food', 'youtube_public_pages', 'en-IN', '["recipe shorts","food reels","quick recipe","street food shorts"]'::jsonb, '["dangerous cooking"]'::jsonb, 0.85),

        ('study', 'google_trends', 'en-IN', '["study with me","exam preparation","study routine","pomodoro","notes making","student motivation"]'::jsonb, '["exam leak","cheating"]'::jsonb, 1.00),
        ('study', 'reddit', 'en-IN', '["study","getstudying","productivity","students","exams"]'::jsonb, '["cheating","piracy"]'::jsonb, 0.90),
        ('study', 'youtube_public_pages', 'en-IN', '["study shorts","study vlog","exam motivation","desk setup study"]'::jsonb, '["answer key leak"]'::jsonb, 0.85),

        ('fashion', 'google_trends', 'en-IN', '["outfit ideas","fashion haul","ethnic wear","street style","wardrobe essentials","styling tips"]'::jsonb, '["counterfeit","fake brand"]'::jsonb, 1.00),
        ('fashion', 'reddit', 'en-IN', '["malefashionadvice","femalefashionadvice","streetwear","indianfashion"]'::jsonb, '["replica scam"]'::jsonb, 0.90),
        ('fashion', 'youtube_public_pages', 'en-IN', '["outfit shorts","fashion reels","styling hacks","wardrobe basics"]'::jsonb, '["counterfeit haul"]'::jsonb, 0.85),

        ('tech_ai', 'google_trends', 'en-IN', '["ai tools","chatgpt","new app","tech tips","automation","gadgets"]'::jsonb, '["hack account","malware"]'::jsonb, 1.00),
        ('tech_ai', 'reddit', 'en-IN', '["artificial","technology","gadgets","productivityapps","chatgpt"]'::jsonb, '["piracy","malware"]'::jsonb, 0.90),
        ('tech_ai', 'youtube_public_pages', 'en-IN', '["ai tools shorts","tech tips shorts","new gadget","automation tutorial"]'::jsonb, '["cracked app"]'::jsonb, 0.85),

        ('finance_crypto', 'google_trends', 'en-IN', '["personal finance","saving money","investing basics","crypto news","bitcoin","mutual funds"]'::jsonb, '["guaranteed returns","pump signal","insider tip"]'::jsonb, 1.00),
        ('finance_crypto', 'reddit', 'en-IN', '["personalfinance","indiainvestments","cryptocurrency","bitcoin","frugal"]'::jsonb, '["pump","signal group","guaranteed"]'::jsonb, 0.90),
        ('finance_crypto', 'youtube_public_pages', 'en-IN', '["finance shorts","money tips","crypto shorts","investing basics"]'::jsonb, '["get rich quick"]'::jsonb, 0.85),

        ('business_startup', 'google_trends', 'en-IN', '["startup ideas","side hustle","small business","saas","marketing tips","founder story"]'::jsonb, '["pyramid scheme","mlm scam"]'::jsonb, 1.00),
        ('business_startup', 'reddit', 'en-IN', '["entrepreneur","startups","smallbusiness","saas","marketing"]'::jsonb, '["mlm","scam"]'::jsonb, 0.90),
        ('business_startup', 'youtube_public_pages', 'en-IN', '["business shorts","startup lessons","side hustle ideas","marketing tips"]'::jsonb, '["get rich quick"]'::jsonb, 0.85),

        ('entertainment', 'google_trends', 'en-IN', '["movie trailer","web series","celebrity news","meme trend","song trend","comedy reels"]'::jsonb, '["pirated movie","leaked scene"]'::jsonb, 1.00),
        ('entertainment', 'reddit', 'en-IN', '["bollywood","movies","television","memes","popculture"]'::jsonb, '["piracy","leak"]'::jsonb, 0.90),
        ('entertainment', 'youtube_public_pages', 'en-IN', '["comedy shorts","movie reaction","song trend","meme shorts"]'::jsonb, '["copyright full movie"]'::jsonb, 0.85),

        ('travel', 'google_trends', 'en-IN', '["travel itinerary","budget travel","weekend getaway","hidden places","solo travel","packing tips"]'::jsonb, '["illegal entry","unsafe stunt"]'::jsonb, 1.00),
        ('travel', 'reddit', 'en-IN', '["travel","solotravel","india_tourism","backpacking","digitalnomad"]'::jsonb, '["illegal","unsafe"]'::jsonb, 0.90),
        ('travel', 'youtube_public_pages', 'en-IN', '["travel shorts","hidden places","budget trip","travel vlog shorts"]'::jsonb, '["trespassing"]'::jsonb, 0.85),

        ('lifestyle_home', 'google_trends', 'en-IN', '["home organization","daily routine","room makeover","cleaning hacks","home decor","family routine"]'::jsonb, '["dangerous hack"]'::jsonb, 1.00),
        ('lifestyle_home', 'reddit', 'en-IN', '["homemaking","organization","cleaningtips","interiordesign","simpleliving"]'::jsonb, '["unsafe chemical"]'::jsonb, 0.90),
        ('lifestyle_home', 'youtube_public_pages', 'en-IN', '["home decor shorts","cleaning hacks","routine shorts","room makeover"]'::jsonb, '["dangerous cleaning"]'::jsonb, 0.85),

        ('self_improvement', 'google_trends', 'en-IN', '["self improvement","discipline","habit building","confidence","motivation","journaling"]'::jsonb, '["medical advice","therapy replacement"]'::jsonb, 1.00),
        ('self_improvement', 'reddit', 'en-IN', '["selfimprovement","decidingtobebetter","getdisciplined","motivation"]'::jsonb, '["crisis","medical diagnosis"]'::jsonb, 0.90),
        ('self_improvement', 'youtube_public_pages', 'en-IN', '["motivation shorts","self improvement shorts","discipline routine","habit building"]'::jsonb, '["toxic advice"]'::jsonb, 0.85)
)
INSERT INTO creator_category_keywords (
    category_id,
    source_type,
    locale,
    include_terms,
    exclude_terms,
    weight,
    active
)
SELECT
    c.id,
    s.source_type,
    s.locale,
    s.include_terms,
    s.exclude_terms,
    s.weight,
    true
FROM keyword_seed s
JOIN creator_categories c ON c.code = s.category_code
ON CONFLICT (category_id, source_type, locale) DO UPDATE
SET include_terms = EXCLUDED.include_terms,
    exclude_terms = EXCLUDED.exclude_terms,
    weight = EXCLUDED.weight,
    active = EXCLUDED.active,
    updated_at = now();
