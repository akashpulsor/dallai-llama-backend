-- V{next}__increase_status_message_length.sql
ALTER TABLE tenants ALTER COLUMN status_message TYPE VARCHAR(2000);
ALTER TABLE tenant_state_audit ALTER COLUMN message TYPE VARCHAR(2000);