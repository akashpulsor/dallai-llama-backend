-- V11__provisioning_logs_and_task_updates.sql

-- ═══════════════════════════════════════════════════════════
-- Add tenant_app_id to provisioning_tasks (was missing)
-- ═══════════════════════════════════════════════════════════
ALTER TABLE provisioning_tasks
    ADD COLUMN IF NOT EXISTS tenant_app_id UUID;

CREATE INDEX IF NOT EXISTS idx_prov_task_app
    ON provisioning_tasks(tenant_app_id);

CREATE INDEX IF NOT EXISTS idx_prov_task_tenant
    ON provisioning_tasks(tenant_id);

-- ═══════════════════════════════════════════════════════════
-- Provisioning logs — immutable audit trail
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS provisioning_logs (
    id              UUID PRIMARY KEY,
    task_id         UUID        NOT NULL,
    tenant_app_id   UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    step            VARCHAR(40) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    attempt_number  INT         NOT NULL DEFAULT 1,
    message         VARCHAR(2000),
    error_detail    VARCHAR(2000),
    duration_ms     BIGINT      NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_prov_log_tenant ON provisioning_logs(tenant_app_id);
CREATE INDEX idx_prov_log_task   ON provisioning_logs(task_id);
CREATE INDEX idx_prov_log_tid    ON provisioning_logs(tenant_id);