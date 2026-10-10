-- Creator Showcase, Phase B: brands (CREATOR_SHOWCASE.md "Brand home").
--
-- A brand signs in with a one-time email link: no password, no Keycloak realm. Its row is the
-- brand directory entry automatic picks will later read (only when auto_picks_opt_in is true).
-- Likes stay anonymous (a browser visitor id); following and requesting a video need sign-in.

CREATE TABLE lead_brand_contact (
    id                 UUID         PRIMARY KEY,
    email              VARCHAR(254) NOT NULL,
    contact_name       VARCHAR(80),
    company_name       VARCHAR(120),
    website_url        VARCHAR(255),
    industry           VARCHAR(32),
    country_code       VARCHAR(2),
    source             VARCHAR(16)  NOT NULL,
    verified_at        TIMESTAMPTZ,
    auto_picks_opt_in  BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_cadence_days  INT          NOT NULL DEFAULT 7,
    last_auto_pick_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_brand_contact_email_uk UNIQUE (email),
    CONSTRAINT lead_brand_contact_source_ck CHECK (source IN ('SIGNUP', 'OPS_IMPORT'))
);

-- One-time sign-in links. Only the sha256 of the token is stored; the token itself exists only
-- in the email.
CREATE TABLE lead_brand_sign_in (
    token_hash        CHAR(64)     PRIMARY KEY,
    brand_contact_id  UUID         NOT NULL,
    pending_action    VARCHAR(255),
    expires_at        TIMESTAMPTZ  NOT NULL,
    used_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_brand_sign_in_contact_fk FOREIGN KEY (brand_contact_id)
        REFERENCES lead_brand_contact (id) ON DELETE CASCADE
);

CREATE TABLE showcase_like (
    showcase_item_id UUID        NOT NULL,
    visitor_id       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT showcase_like_pk PRIMARY KEY (showcase_item_id, visitor_id),
    CONSTRAINT showcase_like_item_fk FOREIGN KEY (showcase_item_id)
        REFERENCES showcase_item (id) ON DELETE CASCADE
);

CREATE TABLE creator_follower (
    tenant_id        UUID        NOT NULL,
    brand_contact_id UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT creator_follower_pk PRIMARY KEY (tenant_id, brand_contact_id),
    CONSTRAINT creator_follower_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT creator_follower_contact_fk FOREIGN KEY (brand_contact_id)
        REFERENCES lead_brand_contact (id) ON DELETE CASCADE
);

ALTER TABLE creator_public_profile ADD COLUMN follower_count INT NOT NULL DEFAULT 0;

CREATE TABLE lead_brand_inquiry (
    id                  UUID          PRIMARY KEY,
    tenant_id           UUID          NOT NULL,
    brand_contact_id    UUID          NOT NULL,
    showcase_item_id    UUID,
    attribution_token   VARCHAR(32),
    budget_band         VARCHAR(16),
    timeline            VARCHAR(16),
    message             VARCHAR(1000) NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    requirement_id      UUID,
    brief_share_token   VARCHAR(100),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_brand_inquiry_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT lead_brand_inquiry_contact_fk FOREIGN KEY (brand_contact_id)
        REFERENCES lead_brand_contact (id) ON DELETE CASCADE,
    CONSTRAINT lead_brand_inquiry_status_ck CHECK (status IN ('NEW', 'VIEWED', 'CONVERTED', 'DECLINED'))
);

CREATE INDEX ix_lead_brand_inquiry_creator ON lead_brand_inquiry (tenant_id, status, created_at DESC);
CREATE INDEX ix_lead_brand_inquiry_brand ON lead_brand_inquiry (brand_contact_id, created_at DESC);
