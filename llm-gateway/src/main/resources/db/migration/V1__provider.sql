CREATE TABLE provider (
    provider_id    VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    adapter_class   VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'active',
    account_rpm     INTEGER,
    account_tpm     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
