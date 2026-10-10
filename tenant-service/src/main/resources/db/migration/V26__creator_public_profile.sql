-- Creator Showcase, Phase A slice 1: the public creator profile, created on subscription.activated
-- alongside the creator email identity (see docs/lead-management/CREATOR_SHOWCASE.md rule 1).
--
-- One profile per tenant. The handle is the public URL key (dalaillama.in/c/<handle>) and the
-- only creator identifier that ever appears in a public payload (rule 19). Ranking columns
-- (level, standing) and the YouTube tables arrive in their own later migrations.

CREATE TABLE creator_public_profile (
    tenant_id         UUID         PRIMARY KEY,
    handle            VARCHAR(40)  NOT NULL,
    handle_changed_at TIMESTAMPTZ,
    display_name      VARCHAR(80)  NOT NULL,
    headline          VARCHAR(120),
    bio               VARCHAR(1000),
    avatar_url        VARCHAR(512),
    country_code      VARCHAR(2),
    website_url       VARCHAR(255),
    auto_picks_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    status            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT creator_public_profile_handle_uk UNIQUE (handle),
    CONSTRAINT creator_public_profile_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT creator_public_profile_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'HIDDEN'))
);

-- The industries a creator works in (max 3, enforced in the service). A join table rather than
-- an array column so JPA maps it as a plain @ElementCollection of an enum.
CREATE TABLE creator_profile_industry (
    tenant_id UUID        NOT NULL,
    industry  VARCHAR(32) NOT NULL,

    CONSTRAINT creator_profile_industry_pk PRIMARY KEY (tenant_id, industry),
    CONSTRAINT creator_profile_industry_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE
);

-- Handles a creator gave up. A released handle cannot be claimed by anyone else until
-- released_at + the configured redirect window, so old profile links keep resolving to the
-- right creator.
CREATE TABLE creator_handle_history (
    handle      VARCHAR(40) PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    released_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT creator_handle_history_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE
);

COMMENT ON TABLE creator_public_profile IS
    'Public creator profile (dalaillama.in/c/<handle>), one per tenant, created on subscription.activated.';
COMMENT ON COLUMN creator_public_profile.status IS
    'ACTIVE (visible), SUSPENDED (subscription lapsed), HIDDEN (ops kill switch).';
