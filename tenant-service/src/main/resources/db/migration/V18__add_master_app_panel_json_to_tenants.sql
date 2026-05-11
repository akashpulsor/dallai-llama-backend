-- V16__add_master_app_panel_json_to_tenants.sql
-- Adds master_app_panel_json column to tenants table.
-- Stores the canonical list of all app panels across products subscribed by the tenant.
-- Individual TenantApp rows may have their own appPanels subset; this column is the union/master.

ALTER TABLE tenants
    ADD COLUMN master_app_panel_json TEXT;

COMMENT ON COLUMN tenants.master_app_panel_json IS
    'Master JSON array of all app panels for this tenant across all subscribed products. Each entry: {appType, displayName, subdomain, url, icon, displayOrder, keycloakClientId, requiredRoles}';