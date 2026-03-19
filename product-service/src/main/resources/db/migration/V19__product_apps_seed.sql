-- ════════════════════════════════════════════════════════════════
-- CORRECTED product_apps seed data
--
-- Subdomains MUST match:
--   Traefik IngressRoute: {subdomain}-{slug}.dalaillama.in
--   K8s Service name:     {subdomain}-ui
--   TraefikHostReconciler SUBDOMAIN_TO_SERVICE map
--
-- enabled=false → UI not built yet, no IngressRoute created,
--                  not shown in app panels
--
-- RUN: psql -d product_service -f product_apps_seed.sql
-- ════════════════════════════════════════════════════════════════

DELETE FROM product_apps;


-- ══════════════════════════════════════════════════════════════
-- AI_CC (AI Contact Center) — 6 apps, 3 active, 3 future
-- ══════════════════════════════════════════════════════════════

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'agent', 'Agent Dashboard', NULL, 'dalaillama/agent-ui:latest', 80, 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0, true
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'SUPERVISOR', 'supervisor', 'Supervisor Dashboard', 'supervisor', 'dalaillama/supervisor-ui:latest', 80, 'SUPERVISOR,TENANT_ADMIN', '👁️', 1, true
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 80, 'TENANT_ADMIN', '⚙️', 2, true
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'IVR_BUILDER', 'ivr', 'IVR Builder', 'ivr', 'dalaillama/ivr-builder:latest', 80, 'TENANT_ADMIN', '🔀', 3, false
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'WALLBOARD', 'wallboard', 'Wallboard', 'wallboard', 'dalaillama/wallboard-ui:latest', 80, 'SUPERVISOR,TENANT_ADMIN,REPORTING_VIEWER', '📊', 4, false
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'REPORTING', 'reports', 'Reporting', 'reporting', 'dalaillama/reporting-ui:latest', 80, 'REPORTING_VIEWER,SUPERVISOR,TENANT_ADMIN', '📈', 5, false
FROM products p WHERE p.code = 'AI_CC';


-- ══════════════════════════════════════════════════════════════
-- CONV_IVR (Conversational IVR) — 3 apps, 2 active, 1 future
-- Agent needed for escalated calls from bot
-- ══════════════════════════════════════════════════════════════

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'agent', 'Agent Dashboard', NULL, 'dalaillama/agent-ui:latest', 80, 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0, true
FROM products p WHERE p.code = 'CONV_IVR';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 80, 'TENANT_ADMIN', '⚙️', 1, true
FROM products p WHERE p.code = 'CONV_IVR';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'IVR_BUILDER', 'ivr', 'IVR Builder', 'ivr', 'dalaillama/ivr-builder:latest', 80, 'TENANT_ADMIN', '🔀', 2, false
FROM products p WHERE p.code = 'CONV_IVR';


-- ══════════════════════════════════════════════════════════════
-- BASIC_PBX — 2 apps
-- ══════════════════════════════════════════════════════════════

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'agent', 'PBX Dashboard', NULL, 'dalaillama/agent-ui:latest', 80, 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0, true
FROM products p WHERE p.code = 'BASIC_PBX';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 80, 'TENANT_ADMIN', '⚙️', 1, true
FROM products p WHERE p.code = 'BASIC_PBX';


-- ══════════════════════════════════════════════════════════════
-- OUTBOUND_DIALER — 3 apps
-- ══════════════════════════════════════════════════════════════

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'agent', 'Dialer Dashboard', NULL, 'dalaillama/agent-ui:latest', 80, 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0, true
FROM products p WHERE p.code = 'OUTBOUND_DIALER';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'SUPERVISOR', 'supervisor', 'Campaign Monitor', 'supervisor', 'dalaillama/supervisor-ui:latest', 80, 'SUPERVISOR,TENANT_ADMIN', '👁️', 1, true
FROM products p WHERE p.code = 'OUTBOUND_DIALER';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 80, 'TENANT_ADMIN', '⚙️', 2, true
FROM products p WHERE p.code = 'OUTBOUND_DIALER';


-- ══════════════════════════════════════════════════════════════
-- VIRTUAL_RECEPTIONIST — 1 app (bot-only, no human agents)
-- ══════════════════════════════════════════════════════════════

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, frontend_port, required_roles, icon, display_order, enabled)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Receptionist Config', NULL, 'dalaillama/admin-ui:latest', 80, 'TENANT_ADMIN', '🤖', 0, true
FROM products p WHERE p.code = 'VIRTUAL_RECEPTIONIST';