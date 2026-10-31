-- Ops mail to creators, part two: creators not on the platform yet (leads ops upload to invite),
-- and a queue every send goes through, so mail leaves a limited number a day (the mailbox is
-- shared) and each recipient has an unsubscribe link.

CREATE TABLE ops_creator_lead (
    id           UUID         PRIMARY KEY,
    email        VARCHAR(254) NOT NULL,
    name         VARCHAR(120),
    -- Their channel or page (YouTube, Instagram, a website), when the upload says.
    profile_url  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ops_creator_lead_email_uk UNIQUE (email)
);

CREATE TABLE ops_creator_mail_delivery (
    id                 UUID         PRIMARY KEY,
    send_id            UUID         NOT NULL REFERENCES ops_creator_mail_send (id),
    template_id        UUID         NOT NULL REFERENCES ops_creator_mail_template (id),
    email              VARCHAR(254) NOT NULL,
    name               VARCHAR(120),
    -- RecipientHasher hash, so an unsubscribe lands in lead_suppression; null without the pepper.
    recipient_hash     CHAR(64),
    unsubscribe_token  VARCHAR(64)  NOT NULL,
    -- QUEUED, SENT, FAILED, or SKIPPED (unsubscribed by the time its turn came).
    status             VARCHAR(8)   NOT NULL DEFAULT 'QUEUED',
    sent_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT ops_creator_mail_delivery_token_uk UNIQUE (unsubscribe_token),
    -- One template reaches an address once, however many times ops send it.
    CONSTRAINT ops_creator_mail_delivery_once_uk UNIQUE (template_id, email)
);

CREATE INDEX ops_creator_mail_delivery_queue_idx ON ops_creator_mail_delivery (status, created_at);
CREATE INDEX ops_creator_mail_delivery_send_idx ON ops_creator_mail_delivery (send_id);

-- The two mails ops send most, ready to edit.
INSERT INTO ops_creator_mail_template (id, name, subject, body) VALUES
('00000000-0000-4000-8000-0000000000c1', 'Profile has no video yet',
 '{name}, add one video so brands can find you',
 'Hi {name},

Your Dalai Llama profile is live, but it has no videos yet, so brands searching on Google and on Dalai Llama can''t see your work.

Add a video to your profile. It''s free and takes two minutes:
https://creator.dalaillama.in/profile

Once it''s there, your profile page can show up in search, and brands can send you video requests directly.

Team Dalai Llama'),
('00000000-0000-4000-8000-0000000000c2', 'Invite a creator to join',
 '{name}, get brand video work on Dalai Llama',
 'Hi {name},

Brands come to Dalai Llama looking for creators to make their videos. Join free, add a video or two to your profile, and brands can find you on Google and send you requests directly.

Create your free profile:
https://creator.dalaillama.in

Team Dalai Llama');
