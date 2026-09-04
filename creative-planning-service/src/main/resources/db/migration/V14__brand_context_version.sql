-- One tenant, one live brand_context row (unchanged -- every one of the ~15 services/call sites
-- that already join on brand_context.id or resolve it by tenant_id keeps working exactly as
-- before). This is a pure, additive history log alongside it, same "separate version table, live
-- row stays in sync" pattern pre-production-service's script_version uses for the same reason:
-- rewriting every existing call site to know which version to join against isn't worth it for a
-- benefit (history/rollback) none of them need. BrandContextService.upsert() snapshots into this
-- on every save. No source/parent_id columns -- unlike Script/Screenplay, nothing here is ever
-- LLM-generated, every version is simply "the brand as the creator saved it at this point".
CREATE TABLE brand_context_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    brand_context_id UUID NOT NULL REFERENCES brand_context(id),
    version INTEGER NOT NULL,
    brand_name VARCHAR(200) NOT NULL,
    industry VARCHAR(200),
    brand_voice TEXT,
    target_audience TEXT,
    brand_values TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_brand_context_version_tenant_version ON brand_context_version(tenant_id, version DESC);

-- Backfill: every existing brand gets an initial version 1 snapshot of its current content.
INSERT INTO brand_context_version (id, tenant_id, brand_context_id, version, brand_name, industry, brand_voice, target_audience, brand_values, created_at)
SELECT gen_random_uuid(), tenant_id, id, 1, brand_name, industry, brand_voice, target_audience, brand_values, created_at
FROM brand_context;
