-- The tenant's account-level authorization role -- CREATOR today (a solo AI video creator);
-- BRAND reserved for a future account type. Distinct from creative-planning-service's own
-- TenantType (COMPANY/AI_VIDEO_CREATOR), which is a per-project-requirement billing-behavior
-- flag, not an account role. Default backfills every existing tenant as CREATOR, matching how
-- every tenant in this system has behaved until now.
ALTER TABLE tenants ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'CREATOR';
