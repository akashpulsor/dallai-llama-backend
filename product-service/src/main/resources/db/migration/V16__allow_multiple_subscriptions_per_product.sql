-- V9__allow_multiple_subscriptions_per_product.sql
-- Allow same tenant to have multiple subscriptions to same product with different DIDs

-- Drop unique constraint
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS uk_subscription_tenant_product;

-- Add unique constraint on tenant + product + did instead
ALTER TABLE subscriptions ADD CONSTRAINT uk_subscription_tenant_product_did
UNIQUE (tenant_id, product_id, did_id);

-- Add index for lookups
CREATE INDEX IF NOT EXISTS idx_subscription_tenant_product ON subscriptions(tenant_id, product_id);
CREATE INDEX IF NOT EXISTS idx_subscription_did ON subscriptions(did_id);