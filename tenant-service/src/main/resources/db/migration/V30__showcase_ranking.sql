-- Creator Showcase, Phase A slice 5: the reinforcement model (CREATOR_SHOWCASE.md rules 11-14).
--
-- A nightly ranking run writes each creator's level and standing, each item's score, and the
-- floors it chose, into the rows below. They are a cache of one run's output, recomputed from
-- raw facts every time, never edited by hand; showcase_ranking_run keeps the history so any
-- day's landing page can be explained.

ALTER TABLE creator_public_profile ADD COLUMN level                   VARCHAR(4) NOT NULL DEFAULT 'L0';
ALTER TABLE creator_public_profile ADD COLUMN standing_factor         NUMERIC(5,4);
ALTER TABLE creator_public_profile ADD COLUMN last_funded_platform_at TIMESTAMPTZ;

ALTER TABLE creator_public_profile ADD CONSTRAINT creator_public_profile_level_ck
    CHECK (level IN ('L0', 'L1', 'L2', 'L3', 'L4'));

ALTER TABLE showcase_item ADD COLUMN like_count      INT          NOT NULL DEFAULT 0;
ALTER TABLE showcase_item ADD COLUMN inquiry_count   INT          NOT NULL DEFAULT 0;
ALTER TABLE showcase_item ADD COLUMN global_score    NUMERIC(8,6);
ALTER TABLE showcase_item ADD COLUMN score_version   VARCHAR(40);
ALTER TABLE showcase_item ADD COLUMN spotlight_until TIMESTAMPTZ;

CREATE INDEX ix_showcase_item_score ON showcase_item (status, global_score DESC);

CREATE TABLE showcase_ranking_run (
    id               UUID         PRIMARY KEY,
    run_at           TIMESTAMPTZ  NOT NULL,
    maturity         NUMERIC(5,4) NOT NULL,
    landing_floor    VARCHAR(4)   NOT NULL,
    top_floor        VARCHAR(4)   NOT NULL,
    auto_floor       VARCHAR(4)   NOT NULL,
    config_version   VARCHAR(40)  NOT NULL,
    creators_ranked  INT          NOT NULL,
    items_scored     INT          NOT NULL
);

CREATE INDEX ix_showcase_ranking_run_at ON showcase_ranking_run (run_at DESC);
