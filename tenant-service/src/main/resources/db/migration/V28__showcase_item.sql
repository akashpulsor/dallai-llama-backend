-- Creator Showcase, Phase A slice 3: the videos a creator shows on their profile, and play
-- counts from our own pages (CREATOR_SHOWCASE.md rules 4, 6, 19).
--
-- An item points at a cached YouTube video. EXTERNAL items are picks from the creator's channel;
-- PLATFORM items (made on Dalaillama, linked to a project) arrive with the publish slice, which
-- fills source_project_id. public_id is the only item id that appears in public URLs.

CREATE TABLE showcase_item (
    id                  UUID         PRIMARY KEY,
    public_id           VARCHAR(12)  NOT NULL,
    tenant_id           UUID         NOT NULL,
    youtube_video_id    VARCHAR(16)  NOT NULL,
    origin              VARCHAR(10)  NOT NULL,
    source_project_id   UUID,
    industry            VARCHAR(32)  NOT NULL,
    format              VARCHAR(32)  NOT NULL,
    client_label        VARCHAR(80),
    title_override      VARCHAR(120),
    status              VARCHAR(16)  NOT NULL,
    hidden_by_ops       BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order          INT          NOT NULL DEFAULT 0,
    play_count          INT          NOT NULL DEFAULT 0,
    full_play_count     INT          NOT NULL DEFAULT 0,
    rights_confirmed_at TIMESTAMPTZ  NOT NULL,
    published_at        TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT showcase_item_public_id_uk UNIQUE (public_id),
    CONSTRAINT showcase_item_video_uk UNIQUE (youtube_video_id),
    CONSTRAINT showcase_item_project_uk UNIQUE (source_project_id),
    CONSTRAINT showcase_item_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT showcase_item_video_fk FOREIGN KEY (youtube_video_id)
        REFERENCES youtube_video (video_id),
    CONSTRAINT showcase_item_origin_ck CHECK (origin IN ('PLATFORM', 'EXTERNAL')),
    CONSTRAINT showcase_item_status_ck CHECK (status IN ('LIVE', 'HIDDEN', 'REMOVED'))
);

CREATE INDEX ix_showcase_item_tenant ON showcase_item (tenant_id, status, sort_order);
CREATE INDEX ix_showcase_item_feed ON showcase_item (status, published_at DESC);

-- One row per visitor per item per day, so a page reload doesn't count twice. visitor_id is a
-- random id the browser keeps; it identifies nobody.
CREATE TABLE showcase_play (
    showcase_item_id UUID    NOT NULL,
    visitor_id       UUID    NOT NULL,
    play_day         DATE    NOT NULL,
    completed        BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT showcase_play_pk PRIMARY KEY (showcase_item_id, visitor_id, play_day),
    CONSTRAINT showcase_play_item_fk FOREIGN KEY (showcase_item_id)
        REFERENCES showcase_item (id) ON DELETE CASCADE
);
