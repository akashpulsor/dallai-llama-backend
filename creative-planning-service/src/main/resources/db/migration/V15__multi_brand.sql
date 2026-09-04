-- Multi-brand: a creator can now manage more than one brand (an agency running several client
-- brands, or a solo creator with more than one business). Every entity that references a brand
-- already stores brand_context_id explicitly per row (product_profile, campaign_planning_session,
-- marketing_plan) rather than re-deriving "the tenant's one brand" each time, so dropping this
-- constraint doesn't orphan or ambiguate any existing data -- it just stops forcing exactly one
-- row per tenant going forward. Existing brands are unaffected and become each tenant's first
-- brand.
ALTER TABLE brand_context DROP CONSTRAINT brand_context_tenant_id_key;
CREATE INDEX idx_brand_context_tenant ON brand_context(tenant_id);

-- Explicit brand link on the requirement itself -- entry point B (standalone brief) may or may not
-- have a product (whose own brand_context_id would otherwise be the only trace of which brand this
-- was for), and now needs its own record of which brand the creator picked, independent of whether
-- a product was created inline. Nullable: a brief with no brand at all is still valid.
ALTER TABLE project_requirement ADD COLUMN brand_context_id UUID REFERENCES brand_context(id);

-- Backfill: every tenant that has ever created a project_requirement had at most one brand before
-- this migration, so that's unambiguously the brand every one of their existing requirements was
-- for.
UPDATE project_requirement pr
SET brand_context_id = (SELECT bc.id FROM brand_context bc WHERE bc.tenant_id = pr.tenant_id LIMIT 1)
WHERE pr.brand_context_id IS NULL;

CREATE INDEX idx_project_requirement_brand ON project_requirement(brand_context_id);

-- brand_context_version (added in V14) was indexed/queried by tenant_id -- now that multiple
-- brands can share a tenant, version numbering and lookups are scoped per-brand instead.
CREATE INDEX idx_brand_context_version_brand ON brand_context_version(brand_context_id, version DESC);
