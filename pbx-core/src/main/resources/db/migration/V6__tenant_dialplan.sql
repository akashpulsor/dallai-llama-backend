-- V6__tenant_dialplan.sql

CREATE TABLE tenant_dialplan (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL UNIQUE,
    subscription_id     UUID NOT NULL,
    context             VARCHAR(100) NOT NULL UNIQUE,     -- "tenant_{slug}" used by FreeSWITCH
    product_code        VARCHAR(30) NOT NULL,
    dialplan_xml        TEXT NOT NULL,                    -- Full FreeSWITCH dialplan XML
    directory_xml       TEXT,                             -- FreeSWITCH directory XML (optional override)
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);