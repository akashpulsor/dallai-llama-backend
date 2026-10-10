-- Creator Showcase, Phase Y (CREATOR_SHOWCASE.md rules 31-40): Google OAuth channel connections
-- for creators and the official channel, publishing films to YouTube, and Chrome extension tokens.

-- One pending OAuth authorisation. Only the sha256 of the state travels back from Google; the
-- PKCE verifier is encrypted (CredentialEncryptor).
CREATE TABLE youtube_oauth_state (
    state_hash      CHAR(64)     PRIMARY KEY,
    owner_type      VARCHAR(16)  NOT NULL,
    tenant_id       UUID,
    started_by      VARCHAR(100) NOT NULL,
    verifier_enc    TEXT         NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT youtube_oauth_state_owner_ck CHECK (owner_type IN ('CREATOR', 'PLATFORM')),
    CONSTRAINT youtube_oauth_state_tenant_ck CHECK ((owner_type = 'CREATOR') = (tenant_id IS NOT NULL))
);

CREATE TABLE youtube_channel_connection (
    id                 UUID          PRIMARY KEY,
    owner_type         VARCHAR(16)   NOT NULL,
    tenant_id          UUID,
    channel_id         VARCHAR(64)   NOT NULL,
    channel_title      VARCHAR(200),
    channel_thumbnail  VARCHAR(500),
    scopes             VARCHAR(1000) NOT NULL,
    refresh_token_enc  TEXT,
    status             VARCHAR(20)   NOT NULL,
    last_error         VARCHAR(500),
    connected_by       VARCHAR(100)  NOT NULL,
    connected_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT youtube_channel_connection_owner_ck CHECK (owner_type IN ('CREATOR', 'PLATFORM')),
    CONSTRAINT youtube_channel_connection_tenant_ck CHECK ((owner_type = 'CREATOR') = (tenant_id IS NOT NULL)),
    CONSTRAINT youtube_channel_connection_status_ck CHECK (status IN ('ACTIVE', 'NEEDS_RECONNECT', 'REVOKED'))
);

-- A creator has at most one live connection, and a channel at most one live owner of each kind.
CREATE UNIQUE INDEX ux_youtube_connection_creator ON youtube_channel_connection (tenant_id)
    WHERE owner_type = 'CREATOR' AND status <> 'REVOKED';
CREATE UNIQUE INDEX ux_youtube_connection_channel ON youtube_channel_connection (owner_type, channel_id)
    WHERE status <> 'REVOKED';

CREATE TABLE youtube_publish_job (
    id                  UUID          PRIMARY KEY,
    tenant_id           UUID          NOT NULL,
    connection_id       UUID          NOT NULL,
    source_project_id   UUID          NOT NULL,
    idempotency_key     VARCHAR(100)  NOT NULL,
    title               VARCHAR(100)  NOT NULL,
    description         VARCHAR(5000) NOT NULL,
    tags                VARCHAR(500),
    category_id         VARCHAR(8)    NOT NULL,
    privacy             VARCHAR(16)   NOT NULL,
    publish_at          TIMESTAMPTZ,
    public_confirmed_at TIMESTAMPTZ,
    thumbnail           BYTEA,
    thumbnail_type      VARCHAR(32),
    status              VARCHAR(16)   NOT NULL,
    upload_url_enc      TEXT,
    bytes_total         BIGINT,
    bytes_sent          BIGINT        NOT NULL DEFAULT 0,
    youtube_video_id    VARCHAR(32),
    youtube_privacy     VARCHAR(16),
    attempts            INT           NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    locked_until        TIMESTAMPTZ,
    last_error          VARCHAR(1000),
    requested_via       VARCHAR(16)   NOT NULL,
    created_by          VARCHAR(100)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT youtube_publish_job_key_uk UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT youtube_publish_job_connection_fk FOREIGN KEY (connection_id) REFERENCES youtube_channel_connection (id),
    CONSTRAINT youtube_publish_job_privacy_ck CHECK (privacy IN ('PRIVATE', 'UNLISTED', 'PUBLIC')),
    CONSTRAINT youtube_publish_job_status_ck CHECK (status IN ('QUEUED', 'UPLOADING', 'PUBLISHED', 'SCHEDULED', 'FAILED', 'CANCELLED')),
    CONSTRAINT youtube_publish_job_via_ck CHECK (requested_via IN ('WEB', 'EXTENSION')),
    CONSTRAINT youtube_publish_job_public_ck CHECK (
        (privacy <> 'PUBLIC' AND publish_at IS NULL) OR public_confirmed_at IS NOT NULL)
);

CREATE INDEX ix_youtube_publish_job_due ON youtube_publish_job (next_attempt_at) WHERE status IN ('QUEUED', 'UPLOADING');
CREATE INDEX ix_youtube_publish_job_tenant ON youtube_publish_job (tenant_id, created_at DESC);

-- Chrome extension pairing (rule 38): only the hash of the token is stored.
CREATE TABLE extension_token (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    user_subject  VARCHAR(100)  NOT NULL,
    token_hash    CHAR(64)      NOT NULL,
    label         VARCHAR(80)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ   NOT NULL,
    revoked_at    TIMESTAMPTZ,

    CONSTRAINT extension_token_hash_uk UNIQUE (token_hash)
);

CREATE INDEX ix_extension_token_tenant ON extension_token (tenant_id, created_at DESC);

-- A channel linked through OAuth is proven by the sign-in, not a description code.
ALTER TABLE creator_youtube_channel ADD COLUMN verified_via VARCHAR(16) NOT NULL DEFAULT 'DESCRIPTION_CODE';
ALTER TABLE creator_youtube_channel ADD CONSTRAINT creator_youtube_channel_via_ck CHECK (verified_via IN ('DESCRIPTION_CODE', 'OAUTH'));
