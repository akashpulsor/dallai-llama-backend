-- V16__make_dids_tenant_id_nullable.sql
ALTER TABLE dids ALTER COLUMN tenant_id DROP NOT NULL;

-- Optional integrity guard: only ACTIVE/PROVISIONING/etc states need a tenant
ALTER TABLE dids ADD CONSTRAINT dids_owned_states_have_tenant
    CHECK (
        status IN ('AVAILABLE', 'RELEASED')
        OR tenant_id IS NOT NULL
    );