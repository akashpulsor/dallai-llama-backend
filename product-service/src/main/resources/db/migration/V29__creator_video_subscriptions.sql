-- Creator-video subscriptions reuse the generic products/plans/subscriptions tables (same pattern
-- as every PBX product) -- this migration only adds the columns those tables didn't need before
-- (billing_cycle) and a new, dedicated entitlement table for this product's own feature set.
-- Nothing here touches an existing PBX row: new columns are nullable and left null on every
-- existing plan/subscription, and plan_entitlements (the PBX feature matrix) is untouched.

ALTER TABLE plans ADD COLUMN IF NOT EXISTS billing_cycle VARCHAR(20);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS billing_cycle VARCHAR(20);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP;

-- One row per Plan, same 1:1 shape as plan_entitlements, but a dedicated table rather than adding
-- video-specific booleans onto the PBX feature matrix (a different product's concerns entirely).
CREATE TABLE IF NOT EXISTS creator_video_plan_entitlements (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL UNIQUE REFERENCES plans(id),
    video_creation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    video_download_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    edits_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    image_upload_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    upscaling_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    upscale_preview_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    character_voice_upload_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    brief_url_share_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed the product + one free plan (auto-assigned, never expires) + three paid cadences at the
-- same canonical price point -- see BillingCycle's javadoc on why each cadence is its own row.
INSERT INTO products (id, code, name, description, type, active, created_at, updated_at, version)
VALUES (gen_random_uuid(), 'CREATOR_VIDEO', 'Creator Video', 'AI video creation for content creators',
        'CREATOR_VIDEO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (code) DO NOTHING;

INSERT INTO plans (id, product_id, code, name, description, tier, monthly_price, currency,
                    is_default, active, billing_cycle, created_at, updated_at, version)
SELECT gen_random_uuid(), p.id, 'CREATOR_VIDEO_FREE', 'Free', 'Create and download videos', 'FREE',
       0, 'INR', TRUE, TRUE, 'MONTHLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM products p WHERE p.code = 'CREATOR_VIDEO'
ON CONFLICT (code) DO NOTHING;

INSERT INTO plans (id, product_id, code, name, description, tier, monthly_price, currency,
                    is_default, active, billing_cycle, created_at, updated_at, version)
SELECT gen_random_uuid(), p.id, 'CREATOR_VIDEO_PRO_MONTHLY', 'Pro (monthly)', 'Full editing, uploads, upscaling and sharing',
       'PROFESSIONAL', 6999, 'INR', FALSE, TRUE, 'MONTHLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM products p WHERE p.code = 'CREATOR_VIDEO'
ON CONFLICT (code) DO NOTHING;

INSERT INTO plans (id, product_id, code, name, description, tier, monthly_price, currency,
                    is_default, active, billing_cycle, created_at, updated_at, version)
SELECT gen_random_uuid(), p.id, 'CREATOR_VIDEO_PRO_QUARTERLY', 'Pro (quarterly)', 'Full editing, uploads, upscaling and sharing',
       'PROFESSIONAL', 6999, 'INR', FALSE, TRUE, 'QUARTERLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM products p WHERE p.code = 'CREATOR_VIDEO'
ON CONFLICT (code) DO NOTHING;

INSERT INTO plans (id, product_id, code, name, description, tier, monthly_price, currency,
                    is_default, active, billing_cycle, created_at, updated_at, version)
SELECT gen_random_uuid(), p.id, 'CREATOR_VIDEO_PRO_YEARLY', 'Pro (yearly)', 'Full editing, uploads, upscaling and sharing',
       'PROFESSIONAL', 6999, 'INR', FALSE, TRUE, 'YEARLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM products p WHERE p.code = 'CREATOR_VIDEO'
ON CONFLICT (code) DO NOTHING;

-- Free plan: only the always-on entitlements (video creation + download).
INSERT INTO creator_video_plan_entitlements (id, plan_id, video_creation_enabled, video_download_enabled,
    edits_enabled, image_upload_enabled, upscaling_enabled, upscale_preview_enabled,
    character_voice_upload_enabled, brief_url_share_enabled, created_at, updated_at)
SELECT gen_random_uuid(), pl.id, TRUE, TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans pl WHERE pl.code = 'CREATOR_VIDEO_FREE'
ON CONFLICT (plan_id) DO NOTHING;

-- Pro plans (all three cadences): everything on.
INSERT INTO creator_video_plan_entitlements (id, plan_id, video_creation_enabled, video_download_enabled,
    edits_enabled, image_upload_enabled, upscaling_enabled, upscale_preview_enabled,
    character_voice_upload_enabled, brief_url_share_enabled, created_at, updated_at)
SELECT gen_random_uuid(), pl.id, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plans pl WHERE pl.code IN ('CREATOR_VIDEO_PRO_MONTHLY', 'CREATOR_VIDEO_PRO_QUARTERLY', 'CREATOR_VIDEO_PRO_YEARLY')
ON CONFLICT (plan_id) DO NOTHING;
