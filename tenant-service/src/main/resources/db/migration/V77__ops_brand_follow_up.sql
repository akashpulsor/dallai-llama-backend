-- Weekly brand inspiration mail: on or off from the ops page (off until ops turn it on), and the
-- templates each stage of the funnel rotates through. Each week brings new videos, so a weekly mail can
-- use a template the address has had; a mail ops send by hand still reaches an address once.
ALTER TABLE ops_creator_mail_delivery ADD COLUMN weekly BOOLEAN NOT NULL DEFAULT FALSE;
DROP INDEX ops_creator_mail_delivery_once_uk;
CREATE UNIQUE INDEX ops_creator_mail_delivery_once_uk ON ops_creator_mail_delivery (template_id, email) WHERE status <> 'TEST' AND NOT weekly;

CREATE TABLE ops_mail_follow_up (
    id          SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
INSERT INTO ops_mail_follow_up (id) VALUES (1);

-- stage: NOT_OPENED, OPENED or CLICKED for the weekly mail; LAST_NOT_OPENED or LAST_OPENED for the one
-- mail after the last weekly one.
CREATE TABLE ops_mail_follow_up_template (
    stage        VARCHAR(20)  NOT NULL,
    template_id  UUID         NOT NULL REFERENCES ops_creator_mail_template (id) ON DELETE CASCADE,
    position     SMALLINT     NOT NULL,
    CONSTRAINT ops_mail_follow_up_template_pk PRIMARY KEY (stage, template_id)
);

-- What a signed-in brand wants mail about: product themes (MailThemes keys, comma separated), which
-- steer its videos, and companies whose ads it would like to see, which ops find videos for.
CREATE TABLE lead_brand_mail_priority (
    brand_contact_id  UUID          PRIMARY KEY REFERENCES lead_brand_contact (id) ON DELETE CASCADE,
    themes            VARCHAR(500)  NOT NULL DEFAULT '',
    ads_from          VARCHAR(500),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- The campaign's other mails, ready to edit; nothing uses them until ops pick them.
INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, style, mail_for) VALUES
('00000000-0000-4000-8000-0000000000b4', 'Weekly: more video ideas (styled)',
 '{name}, more video ideas for {company}',
 'Hi {name},

Here are this week''s video ideas for {company}, picked from our inspiration board. If one of them fits, we can get it made for you.',
 'Get a video made for {company}', 'https://dalaillama.in', 3, 'STYLED', 'BRANDS'),
('00000000-0000-4000-8000-0000000000b5', 'Last mail: another kind of inspiration? (styled)',
 '{name}, want a different kind of video inspiration?',
 'Hi {name},

We''ve been sending {company} video ideas for a few weeks. If they weren''t quite what you''re after, tell us what you''d like to see instead.

It''s free, and takes a minute.',
 'Tell us what you''d like', 'https://platform.dalaillama.in/brands/home', 0, 'STYLED', 'BRANDS'),
('00000000-0000-4000-8000-0000000000b6', 'Last mail: your priorities (styled)',
 '{name}, what video ideas should we find for {company}?',
 'Hi {name},

Thanks for taking a look at the video ideas we''ve sent. Tell us your priorities: the kinds of video you want to see and the companies whose ads you like, and we''ll find videos for {company} that match.

It''s free, and takes a minute.',
 'Tell us your priorities', 'https://platform.dalaillama.in/brands/home', 0, 'STYLED', 'BRANDS');
