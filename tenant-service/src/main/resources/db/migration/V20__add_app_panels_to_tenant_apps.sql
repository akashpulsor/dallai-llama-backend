-- V19__add_app_panels_to_tenant_apps.sql
-- Adds app_panels JSONB column to tenant_apps.
-- Stores the panel definitions received via SubscriptionActiveRequest.productApps()
-- when a subscription becomes ACTIVE. Consumed by stepCreateKeycloakClient in the
-- provisioning orchestrator, which parses this JSON and upserts panels into the
-- tenant_app_panels relational table.

ALTER TABLE tenant_apps
    ADD COLUMN app_panels JSONB;

COMMENT ON COLUMN tenant_apps.app_panels IS
    'JSON array of UI panel definitions for this subscription. Each entry: {appType, displayName, subdomain, url, icon, displayOrder}. Populated from SubscriptionActiveRequest.productApps. Consumed by provisioning orchestrator and copied (enriched) into tenant_app_panels.';