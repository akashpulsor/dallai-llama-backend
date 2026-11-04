-- Ops mail history: what each mail showed and what its recipient did with it, so ops can see what a
-- brand was sent and which templates work. Kept when a video is later taken off the board: the title
-- and picture are copied, only the link to the board item goes null.
ALTER TABLE ops_creator_mail_delivery ADD COLUMN opened_at TIMESTAMPTZ;
ALTER TABLE ops_creator_mail_delivery ADD COLUMN first_clicked_at TIMESTAMPTZ;
-- A brand mail to a brand known to sell something (its category, name or website read as a product
-- theme): whether one of its videos was about that. Null for any other mail.
ALTER TABLE ops_creator_mail_delivery ADD COLUMN videos_fit BOOLEAN;

CREATE INDEX ix_ops_creator_mail_delivery_email ON ops_creator_mail_delivery (email, sent_at DESC);

CREATE TABLE ops_mail_delivery_video (
    delivery_id  UUID         NOT NULL,
    position     SMALLINT     NOT NULL,
    item_id      UUID,
    title        VARCHAR(200) NOT NULL,
    poster_url   VARCHAR(500),
    CONSTRAINT ops_mail_delivery_video_pk PRIMARY KEY (delivery_id, position),
    CONSTRAINT ops_mail_delivery_video_delivery_fk FOREIGN KEY (delivery_id) REFERENCES ops_creator_mail_delivery (id) ON DELETE CASCADE,
    CONSTRAINT ops_mail_delivery_video_item_fk FOREIGN KEY (item_id) REFERENCES inspiration_item (id) ON DELETE SET NULL
);

-- Each link a mail carried (a video, "watch them here", its button), as /api/v1/public/outreach/ops/c/{delivery}/{position}.
CREATE TABLE ops_mail_delivery_link (
    delivery_id       UUID          NOT NULL,
    position          SMALLINT      NOT NULL,
    label             VARCHAR(200)  NOT NULL,
    url               VARCHAR(1000) NOT NULL,
    clicks            INT           NOT NULL DEFAULT 0,
    first_clicked_at  TIMESTAMPTZ,
    CONSTRAINT ops_mail_delivery_link_pk PRIMARY KEY (delivery_id, position),
    CONSTRAINT ops_mail_delivery_link_delivery_fk FOREIGN KEY (delivery_id) REFERENCES ops_creator_mail_delivery (id) ON DELETE CASCADE
);
