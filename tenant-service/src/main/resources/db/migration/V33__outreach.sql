-- Creator Showcase, Phase C: outreach (CREATOR_SHOWCASE.md rules 9, 15, 16, 17).
--
-- Every non-transactional brand mail starts as an intent. A recipient gets at most one delivery
-- a day (UNIQUE recipient_hash + delivery_day): the creator's mail goes out at once if the
-- recipient has had nothing today, otherwise it waits for the next daily digest. Rules key on
-- recipient_hash (sha256 of pepper + email); the raw address, name and company are purged 30
-- days after delivery.

CREATE TABLE lead_suppression (
    recipient_hash CHAR(64)    PRIMARY KEY,
    reason         VARCHAR(16) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_suppression_reason_ck CHECK (reason IN ('UNSUBSCRIBED', 'BOUNCED', 'COMPLAINED', 'OPS'))
);

CREATE TABLE lead_mail_pack (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    idempotency_key  VARCHAR(100)  NOT NULL,
    quantity         INT           NOT NULL,
    remaining        INT           NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    purchased_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_mail_pack_key_uk UNIQUE (idempotency_key),
    CONSTRAINT lead_mail_pack_remaining_ck CHECK (remaining >= 0 AND remaining <= quantity)
);

CREATE INDEX ix_lead_mail_pack_tenant ON lead_mail_pack (tenant_id, purchased_at) WHERE remaining > 0;

CREATE TABLE lead_outreach_delivery (
    id                 UUID         PRIMARY KEY,
    recipient_hash     CHAR(64)     NOT NULL,
    delivery_day       DATE         NOT NULL,
    kind               VARCHAR(16)  NOT NULL,
    tenant_id          UUID,
    recipient_email    VARCHAR(254),
    subject            VARCHAR(200) NOT NULL,
    unsubscribe_token  VARCHAR(32)  NOT NULL,
    sent                BOOLEAN     NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    purged_at          TIMESTAMPTZ,

    CONSTRAINT lead_outreach_delivery_day_uk UNIQUE (recipient_hash, delivery_day),
    CONSTRAINT lead_outreach_delivery_unsub_uk UNIQUE (unsubscribe_token),
    CONSTRAINT lead_outreach_delivery_kind_ck CHECK (kind IN ('CREATOR_MAIL', 'DIGEST'))
);

CREATE TABLE lead_outreach_intent (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    recipient_hash    CHAR(64)      NOT NULL,
    recipient_email   VARCHAR(254),
    recipient_name    VARCHAR(80),
    company_name      VARCHAR(120),
    template          VARCHAR(24)   NOT NULL,
    origin            VARCHAR(16)   NOT NULL,
    showcase_item_id  UUID,
    personal_note     VARCHAR(500),
    status            VARCHAR(16)   NOT NULL,
    mail_pack_id      UUID,
    delivery_id       UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    decided_at        TIMESTAMPTZ,
    purged_at         TIMESTAMPTZ,

    CONSTRAINT lead_outreach_intent_template_ck CHECK (template IN
        ('SHOWCASE_WORK', 'SIMILAR_BRAND_WORK', 'CREATOR_PORTFOLIO', 'NEW_FILM')),
    CONSTRAINT lead_outreach_intent_origin_ck CHECK (origin IN ('CREATOR', 'AUTO_PICK', 'FOLLOW')),
    CONSTRAINT lead_outreach_intent_status_ck CHECK (status IN ('PENDING', 'SENT', 'DIGESTED', 'EXPIRED', 'FAILED')),
    CONSTRAINT lead_outreach_intent_delivery_fk FOREIGN KEY (delivery_id) REFERENCES lead_outreach_delivery (id),
    CONSTRAINT lead_outreach_intent_pack_fk FOREIGN KEY (mail_pack_id) REFERENCES lead_mail_pack (id)
);

CREATE INDEX ix_lead_outreach_intent_pending ON lead_outreach_intent (recipient_hash, created_at) WHERE status = 'PENDING';
CREATE INDEX ix_lead_outreach_intent_creator ON lead_outreach_intent (tenant_id, created_at DESC);
CREATE INDEX ix_lead_outreach_intent_cooldown ON lead_outreach_intent (tenant_id, recipient_hash, created_at DESC);

-- One tracked link per film card in a delivery: /api/v1/public/outreach/r/{token}.
CREATE TABLE lead_outreach_link (
    token             VARCHAR(32)  PRIMARY KEY,
    delivery_id       UUID         NOT NULL,
    tenant_id         UUID         NOT NULL,
    showcase_item_id  UUID,
    target_url        VARCHAR(500) NOT NULL,
    click_count       INT          NOT NULL DEFAULT 0,
    first_clicked_at  TIMESTAMPTZ,

    CONSTRAINT lead_outreach_link_delivery_fk FOREIGN KEY (delivery_id) REFERENCES lead_outreach_delivery (id) ON DELETE CASCADE
);

CREATE INDEX ix_lead_outreach_link_creator ON lead_outreach_link (tenant_id);
