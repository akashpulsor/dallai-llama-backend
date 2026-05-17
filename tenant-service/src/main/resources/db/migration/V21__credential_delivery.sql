-- V21: tenant_users + user_credential_deliveries
--
-- tenant_users: identity tracking for ADMIN, SUPERVISOR, and AGENT users.
-- user_credential_deliveries: encrypted provisioned passwords for subscription details.

CREATE TABLE tenant_users (
    id                   UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL REFERENCES tenants(id),
    keycloak_user_id     VARCHAR(100) NOT NULL,
    username             VARCHAR(100) NOT NULL,
    email                VARCHAR(255),
    first_name           VARCHAR(100),
    last_name            VARCHAR(100),
    primary_role         VARCHAR(20)  NOT NULL,
    roles_csv            VARCHAR(500),
    status               VARCHAR(20)  NOT NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT true,
    first_login_at       TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    last_login_ip        VARCHAR(45),
    login_count          BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       DEFAULT 0,

    CONSTRAINT uk_tenant_users_tenant_kc   UNIQUE (tenant_id, keycloak_user_id),
    CONSTRAINT uk_tenant_users_tenant_user UNIQUE (tenant_id, username)
);

CREATE INDEX ix_tenant_users_kc_id ON tenant_users (keycloak_user_id);

CREATE TABLE user_credential_deliveries (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    tenant_user_id    UUID         NOT NULL REFERENCES tenant_users(id),
    keycloak_user_id  VARCHAR(100) NOT NULL,
    username          VARCHAR(100) NOT NULL,
    temp_password_enc VARCHAR(500),
    login_url         VARCHAR(300) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    viewed_by         VARCHAR(100),
    viewed_at         TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_cred_del_tenant_status ON user_credential_deliveries (tenant_id, status);
CREATE INDEX ix_cred_del_kc_status     ON user_credential_deliveries (keycloak_user_id, status);
CREATE INDEX ix_cred_del_expires       ON user_credential_deliveries (expires_at);
