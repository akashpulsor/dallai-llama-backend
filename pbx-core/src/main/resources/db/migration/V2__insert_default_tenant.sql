INSERT INTO tenant (id, name, status, deployment_model)
VALUES ('tenant_default', 'Default Tenant', 'active', 'shared')
ON DUPLICATE KEY UPDATE name='Default Tenant';
