-- Ops mail to creators: templates ops write once and reuse, and a log of each send so ops can see
-- what went to whom and when.
CREATE TABLE ops_creator_mail_template (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    subject     VARCHAR(200) NOT NULL,
    body        TEXT         NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE ops_creator_mail_send (
    id           UUID         PRIMARY KEY,
    template_id  UUID         NOT NULL REFERENCES ops_creator_mail_template (id),
    subject      VARCHAR(200) NOT NULL,
    -- Who it went to: ALL (every active creator), CHOSEN (picked by ops) or TEST (one address).
    audience     VARCHAR(8)   NOT NULL,
    recipients   INT          NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ops_creator_mail_send_template_idx ON ops_creator_mail_send (template_id, created_at DESC);
