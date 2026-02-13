
-- Create product_apps table to manage different applications under each product
CREATE TABLE product_apps (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    app_type VARCHAR(50) NOT NULL,
    subdomain VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    keycloak_client_suffix VARCHAR(50),
    frontend_image VARCHAR(200) NOT NULL,
    frontend_port INTEGER NOT NULL DEFAULT 80,
    required_roles VARCHAR(200),
    icon VARCHAR(200),
    description VARCHAR(500),
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_product_app_type UNIQUE (product_id, app_type)
);

CREATE INDEX idx_product_apps_product_id ON product_apps(product_id);

-- Seed data for AI_CONTACT_CENTER product
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'app', 'Contact Center', NULL, 'dalaillama/agent-ui:latest', 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'IVR_BUILDER', 'ivr', 'IVR Builder', 'ivr', 'dalaillama/ivr-builder:latest', 'TENANT_ADMIN', '🔀', 1
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 'TENANT_ADMIN', '⚙️', 2
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'SUPERVISOR', 'supervisor', 'Supervisor Dashboard', 'supervisor', 'dalaillama/supervisor-ui:latest', 'SUPERVISOR,TENANT_ADMIN', '👁️', 3
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'WALLBOARD', 'wallboard', 'Wallboard', 'wallboard', 'dalaillama/wallboard-ui:latest', 'SUPERVISOR,TENANT_ADMIN,REPORTING_VIEWER', '📊', 4
FROM products p WHERE p.code = 'AI_CC';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'REPORTING', 'reports', 'Reporting', 'reporting', 'dalaillama/reporting-ui:latest', 'REPORTING_VIEWER,SUPERVISOR,TENANT_ADMIN', '📈', 5
FROM products p WHERE p.code = 'AI_CC';

-- Seed data for CONVERSATIONAL_IVR product (simpler, fewer apps)
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'IVR_BUILDER', 'ivr', 'IVR Builder', NULL, 'dalaillama/ivr-builder:latest', 'TENANT_ADMIN', '🔀', 0
FROM products p WHERE p.code = 'CONV_IVR';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 'TENANT_ADMIN', '⚙️', 1
FROM products p WHERE p.code = 'CONV_IVR';

-- Seed data for BASIC_PBX product
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'CONTACT_CENTER', 'app', 'PBX Dashboard', NULL, 'dalaillama/pbx-ui:latest', 'AGENT,SUPERVISOR,TENANT_ADMIN', '📞', 0
FROM products p WHERE p.code = 'BASIC_PBX';

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 'TENANT_ADMIN', '⚙️', 1
FROM products p WHERE p.code = 'BASIC_PBX';

