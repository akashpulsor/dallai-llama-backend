-- Creator Showcase, Phase A slice 2: a creator's linked YouTube channel and our cache of its
-- videos (CREATOR_SHOWCASE.md rules 2 and 6).
--
-- Ownership is proven by a code the creator puts in the channel description, read with an API
-- key: no OAuth. YouTube's developer policies let us keep ids (channel_id, video_id) as long as
-- we like, but other API data (titles, thumbnails, status, durations) for at most 30 days, so
-- every cached row carries fetched_at and a scheduled job refreshes or hides it.

CREATE TABLE creator_youtube_channel (
    tenant_id            UUID         PRIMARY KEY,
    channel_id           VARCHAR(32)  NOT NULL,
    channel_title        VARCHAR(200),
    channel_thumbnail_url VARCHAR(512),
    uploads_playlist_id  VARCHAR(40)  NOT NULL,
    verification_code    VARCHAR(16)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    verified_at          TIMESTAMPTZ,
    last_synced_at       TIMESTAMPTZ,
    fetched_at           TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT creator_youtube_channel_channel_uk UNIQUE (channel_id),
    CONSTRAINT creator_youtube_channel_profile_fk FOREIGN KEY (tenant_id)
        REFERENCES creator_public_profile (tenant_id) ON DELETE CASCADE,
    CONSTRAINT creator_youtube_channel_status_ck CHECK (status IN ('PENDING', 'VERIFIED'))
);

CREATE TABLE youtube_video (
    video_id          VARCHAR(16)  PRIMARY KEY,
    channel_id        VARCHAR(32)  NOT NULL,
    title             VARCHAR(200),
    thumbnail_url     VARCHAR(512),
    published_at      TIMESTAMPTZ,
    duration_seconds  NUMERIC(8,2),
    aspect_w          INT,
    aspect_h          INT,
    privacy_status    VARCHAR(12),
    embeddable        BOOLEAN,
    age_restricted    BOOLEAN,
    made_for_kids     BOOLEAN,
    fetched_at        TIMESTAMPTZ  NOT NULL,
    gone_at           TIMESTAMPTZ
);

CREATE INDEX ix_youtube_video_channel ON youtube_video (channel_id, published_at DESC);
CREATE INDEX ix_youtube_video_fetched ON youtube_video (fetched_at) WHERE gone_at IS NULL;

COMMENT ON TABLE youtube_video IS
    'Cache of YouTube API data. Ids are kept; every other column must be refreshed within 30 days of fetched_at or the row hidden.';
COMMENT ON COLUMN youtube_video.gone_at IS
    'Set when a refresh finds the video deleted or no longer public; such videos never show.';
