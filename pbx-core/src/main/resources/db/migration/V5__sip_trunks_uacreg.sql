-- V5__sip_trunks_uacreg.sql

CREATE TABLE sip_trunks (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID NOT NULL,
    subscription_id             UUID NOT NULL,
    name                        VARCHAR(100) NOT NULL,
    provider                    VARCHAR(50),              -- DIDWW, TATA, AIRTEL, CUSTOM
    sip_server                  VARCHAR(200) NOT NULL,
    sip_port                    INT DEFAULT 5060,
    transport                   VARCHAR(10) DEFAULT 'UDP',
    auth_type                   VARCHAR(20) DEFAULT 'DIGEST',
    auth_username               VARCHAR(100),
    auth_password_encrypted     TEXT,
    max_concurrent_outbound     INT DEFAULT 10,
    outbound_caller_id          VARCHAR(20),
    codec_preference            VARCHAR(200),
    dispatcher_set_id           INT,                      -- Links to dispatcher.setid
    is_active                   BOOLEAN DEFAULT TRUE,
    created_at                  TIMESTAMP DEFAULT NOW(),
    updated_at                  TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);