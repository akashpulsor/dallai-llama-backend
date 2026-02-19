
-- ============================================================
-- V8__update_pstn_channel_bundles.sql
-- ============================================================

ALTER TABLE pstn_channel_bundles
ADD COLUMN IF NOT EXISTS direction VARCHAR(20) DEFAULT 'BOTH';

ALTER TABLE pstn_channel_bundles
ADD COLUMN IF NOT EXISTS product_code VARCHAR(50);

ALTER TABLE pstn_channel_bundles
ADD COLUMN IF NOT EXISTS subscription_id UUID;

ALTER TABLE pstn_channel_bundles
ALTER COLUMN inbound_channels DROP NOT NULL;

ALTER TABLE pstn_channel_bundles
ALTER COLUMN outbound_channels DROP NOT NULL;

UPDATE pstn_channel_bundles SET direction = 'BOTH' WHERE direction IS NULL;