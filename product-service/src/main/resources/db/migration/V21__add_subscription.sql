ALTER TABLE IF EXISTS subscriptions
ADD COLUMN IF NOT EXISTS tenant_app_id UUID;

CREATE INDEX IF NOT EXISTS idx_subscription_id
ON subscriptions (id);

CREATE INDEX IF NOT EXISTS idx_subscription_tenant_app_id
ON subscriptions (tenant_app_id);