-- Creator-video plans as sold on the landing page: Free, Studio (2,999 a month) and Pro (6,999 a
-- month), each paid monthly, quarterly, every 6 months or yearly. A plan row's monthly_price is what
-- one renewal of that row charges, so a longer cadence stores its whole period's price: the monthly
-- price times the months, less 10% (quarterly), 15% (6 months) or 20% (yearly).
-- Only monthly Pro subscriptions exist so far; existing subscriptions keep their snapshot price.

-- Outreach caps per plan: emails to brands a month, and how many brands the creator's work reaches
-- through automatic outreach (NULL = every brand).
ALTER TABLE creator_video_plan_entitlements ADD COLUMN IF NOT EXISTS monthly_outreach_emails INT NOT NULL DEFAULT 100;
ALTER TABLE creator_video_plan_entitlements ADD COLUMN IF NOT EXISTS brand_reach INT DEFAULT 10;

UPDATE plans SET monthly_price = 18897, updated_at = CURRENT_TIMESTAMP WHERE code = 'CREATOR_VIDEO_PRO_QUARTERLY';
UPDATE plans SET monthly_price = 67190, updated_at = CURRENT_TIMESTAMP WHERE code = 'CREATOR_VIDEO_PRO_YEARLY';

INSERT INTO plans (id, product_id, code, name, description, tier, monthly_price, currency,
                   is_default, active, billing_cycle, created_at, updated_at, version)
SELECT gen_random_uuid(), p.id, v.code, v.name, v.description, v.tier, v.price, 'INR', FALSE, TRUE, v.cycle,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM products p
CROSS JOIN (VALUES
    ('CREATOR_VIDEO_STUDIO_MONTHLY', 'Studio (monthly)', 'Editing, image uploads and brief sharing', 'STARTER', 2999, 'MONTHLY'),
    ('CREATOR_VIDEO_STUDIO_QUARTERLY', 'Studio (quarterly)', 'Editing, image uploads and brief sharing', 'STARTER', 8097, 'QUARTERLY'),
    ('CREATOR_VIDEO_STUDIO_HALF_YEARLY', 'Studio (6 months)', 'Editing, image uploads and brief sharing', 'STARTER', 15295, 'HALF_YEARLY'),
    ('CREATOR_VIDEO_STUDIO_YEARLY', 'Studio (yearly)', 'Editing, image uploads and brief sharing', 'STARTER', 28790, 'YEARLY'),
    ('CREATOR_VIDEO_PRO_HALF_YEARLY', 'Pro (6 months)', 'Full editing, uploads, upscaling and sharing', 'PROFESSIONAL', 35695, 'HALF_YEARLY')
) AS v(code, name, description, tier, price, cycle)
WHERE p.code = 'CREATOR_VIDEO'
ON CONFLICT (code) DO NOTHING;

-- Studio: creation, download, edits, image uploads, brief sharing; no upscaling or voice upload.
INSERT INTO creator_video_plan_entitlements (id, plan_id, video_creation_enabled, video_download_enabled,
    edits_enabled, image_upload_enabled, upscaling_enabled, upscale_preview_enabled,
    character_voice_upload_enabled, brief_url_share_enabled, monthly_outreach_emails, brand_reach, created_at, updated_at)
SELECT gen_random_uuid(), pl.id, TRUE, TRUE, TRUE, TRUE, FALSE, FALSE, FALSE, TRUE, 1000, 100,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans pl WHERE pl.code LIKE 'CREATOR_VIDEO_STUDIO_%'
ON CONFLICT (plan_id) DO NOTHING;

-- Pro (6 months): everything, like the other Pro cadences.
INSERT INTO creator_video_plan_entitlements (id, plan_id, video_creation_enabled, video_download_enabled,
    edits_enabled, image_upload_enabled, upscaling_enabled, upscale_preview_enabled,
    character_voice_upload_enabled, brief_url_share_enabled, monthly_outreach_emails, brand_reach, created_at, updated_at)
SELECT gen_random_uuid(), pl.id, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, 3000, NULL,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans pl WHERE pl.code = 'CREATOR_VIDEO_PRO_HALF_YEARLY'
ON CONFLICT (plan_id) DO NOTHING;

UPDATE creator_video_plan_entitlements e SET monthly_outreach_emails = 100, brand_reach = 10
FROM plans pl WHERE pl.id = e.plan_id AND pl.code = 'CREATOR_VIDEO_FREE';
UPDATE creator_video_plan_entitlements e SET monthly_outreach_emails = 3000, brand_reach = NULL
FROM plans pl WHERE pl.id = e.plan_id AND pl.code LIKE 'CREATOR_VIDEO_PRO_%';
