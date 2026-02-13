ALTER TABLE tenants
ADD COLUMN product_code VARCHAR(50);

-- Recommended: Add a comment to describe its purpose
COMMENT ON COLUMN tenants.product_code IS 'Top-level product identifier (e.g., CC_CORE, CC_AI_PRO)';