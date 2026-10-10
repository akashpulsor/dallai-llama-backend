-- Creator Showcase, Phase E (CREATOR_SHOWCASE.md rules 21-30): creator audiences, de-duplicated
-- leads with validated contact points, creator + global email templates, and audience sends
-- through the dispatcher. Table names follow docs/lead-management/PLATFORM.md.

-- Platform-level contact points (Level 3). No creator data here: a creator's link to a contact
-- point lives in lead_creator_lead_contact_point.
CREATE TABLE lead_contact_point (
    id                UUID         PRIMARY KEY,
    kind              VARCHAR(8)   NOT NULL,
    value             VARCHAR(254) NOT NULL,
    -- RecipientHasher hash of an email, so suppression and engagement can find it. Null until
    -- the hash pepper is set (the validation job fills it in).
    value_hash        CHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'UNVERIFIED',
    status_reason     VARCHAR(24),
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_checked_at   TIMESTAMPTZ,

    CONSTRAINT lead_contact_point_value_uk UNIQUE (kind, value),
    CONSTRAINT lead_contact_point_kind_ck CHECK (kind IN ('EMAIL', 'PHONE')),
    CONSTRAINT lead_contact_point_status_ck CHECK (status IN ('VERIFIED', 'LIKELY_VALID', 'UNVERIFIED', 'INVALID'))
);

CREATE INDEX ix_lead_contact_point_unverified ON lead_contact_point (first_seen_at) WHERE status = 'UNVERIFIED' AND kind = 'EMAIL';
CREATE INDEX ix_lead_contact_point_hash ON lead_contact_point (value_hash) WHERE value_hash IS NOT NULL;

-- A creator's lead (Level 1 identity, private to that creator).
CREATE TABLE lead_creator_lead (
    id            UUID         PRIMARY KEY,
    tenant_id     UUID         NOT NULL,
    display_name  VARCHAR(120),
    company_name  VARCHAR(160),
    industry      VARCHAR(32),
    website_url   VARCHAR(255),
    -- Strict creation order: merges keep the earliest lead even within one upload.
    created_order BIGINT       GENERATED ALWAYS AS IDENTITY,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_lead_creator_lead_tenant ON lead_creator_lead (tenant_id, created_at);

CREATE TABLE lead_creator_lead_contact_point (
    lead_id           UUID        NOT NULL,
    contact_point_id  UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    times_seen        INT         NOT NULL DEFAULT 1,
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_creator_lead_cp_pk PRIMARY KEY (lead_id, contact_point_id),
    -- Within one creator, a contact point belongs to exactly one lead: this is the dedupe key.
    CONSTRAINT lead_creator_lead_cp_tenant_uk UNIQUE (tenant_id, contact_point_id),
    CONSTRAINT lead_creator_lead_cp_lead_fk FOREIGN KEY (lead_id) REFERENCES lead_creator_lead (id) ON DELETE CASCADE,
    CONSTRAINT lead_creator_lead_cp_point_fk FOREIGN KEY (contact_point_id) REFERENCES lead_contact_point (id)
);

CREATE TABLE lead_saved_audience (
    id          UUID         PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(80)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_saved_audience_name_uk UNIQUE (tenant_id, name)
);

CREATE TABLE lead_saved_audience_member (
    audience_id  UUID        NOT NULL,
    lead_id      UUID        NOT NULL,
    added_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_saved_audience_member_pk PRIMARY KEY (audience_id, lead_id),
    CONSTRAINT lead_saved_audience_member_audience_fk FOREIGN KEY (audience_id) REFERENCES lead_saved_audience (id) ON DELETE CASCADE,
    CONSTRAINT lead_saved_audience_member_lead_fk FOREIGN KEY (lead_id) REFERENCES lead_creator_lead (id) ON DELETE CASCADE
);

CREATE TABLE lead_import_batch (
    id                    UUID         PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    audience_id           UUID,
    file_name             VARCHAR(200),
    rows_total            INT          NOT NULL,
    rows_imported         INT          NOT NULL,
    rows_rejected         INT          NOT NULL,
    leads_created         INT          NOT NULL,
    leads_merged          INT          NOT NULL,
    contact_points_added  INT          NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_import_batch_audience_fk FOREIGN KEY (audience_id) REFERENCES lead_saved_audience (id) ON DELETE SET NULL
);

-- Verbatim uploaded rows (Level 1): never rewritten by normalisation.
CREATE TABLE lead_creator_lead_source (
    id               UUID         PRIMARY KEY,
    import_batch_id  UUID         NOT NULL,
    tenant_id        UUID         NOT NULL,
    lead_id          UUID,
    row_number       INT          NOT NULL,
    raw_row          TEXT         NOT NULL,
    rejected_reason  VARCHAR(120),

    CONSTRAINT lead_creator_lead_source_batch_fk FOREIGN KEY (import_batch_id) REFERENCES lead_import_batch (id) ON DELETE CASCADE,
    CONSTRAINT lead_creator_lead_source_lead_fk FOREIGN KEY (lead_id) REFERENCES lead_creator_lead (id) ON DELETE SET NULL
);

-- Email templates: tenant_id NULL = global (ops-managed, visible to every creator).
CREATE TABLE lead_email_template (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID,
    name        VARCHAR(80)   NOT NULL,
    layout      VARCHAR(24)   NOT NULL,
    subject     VARCHAR(200)  NOT NULL,
    intro       VARCHAR(1000) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_email_template_layout_ck CHECK (layout IN ('SHOWCASE_WORK', 'SIMILAR_BRAND_WORK', 'CREATOR_PORTFOLIO'))
);

CREATE INDEX ix_lead_email_template_tenant ON lead_email_template (tenant_id);

-- The three built-in mails, as global templates (same copy as OutreachComposer's defaults).
INSERT INTO lead_email_template (id, tenant_id, name, layout, subject, intro) VALUES
    ('00000000-0000-4000-8000-0000000000a1', NULL, 'Share a film', 'SHOWCASE_WORK',
     '{film} · a film by {creator}', 'I wanted to share a film I made recently.'),
    ('00000000-0000-4000-8000-0000000000a2', NULL, 'Made for a brand like yours', 'SIMILAR_BRAND_WORK',
     'A film I made for a {industry} brand',
     'I recently made this film for a brand in {industry}, and thought it might be useful for yours.'),
    ('00000000-0000-4000-8000-0000000000a3', NULL, 'My recent work', 'CREATOR_PORTFOLIO',
     'Some of my recent films · {creator}', 'Here are a few films I''ve made for brands recently.');

-- Intents: QUEUED = waiting for the dispatcher (audience sends); PENDING keeps meaning "waiting
-- for the recipient's next digest". Each intent records its template and audience.
ALTER TABLE lead_outreach_intent DROP CONSTRAINT lead_outreach_intent_status_ck;
ALTER TABLE lead_outreach_intent ADD CONSTRAINT lead_outreach_intent_status_ck
    CHECK (status IN ('QUEUED', 'PENDING', 'SENT', 'DIGESTED', 'EXPIRED', 'FAILED'));
ALTER TABLE lead_outreach_intent ADD COLUMN email_template_id UUID REFERENCES lead_email_template (id);
ALTER TABLE lead_outreach_intent ADD COLUMN audience_id UUID REFERENCES lead_saved_audience (id) ON DELETE SET NULL;
ALTER TABLE lead_outreach_intent ADD COLUMN lead_id UUID REFERENCES lead_creator_lead (id) ON DELETE SET NULL;

CREATE INDEX ix_lead_outreach_intent_queued ON lead_outreach_intent (created_at) WHERE status = 'QUEUED';
CREATE INDEX ix_lead_outreach_intent_template ON lead_outreach_intent (tenant_id, email_template_id);
