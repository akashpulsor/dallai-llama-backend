-- The Inspiration board: videos from Pinterest that ops pick per industry, shown to brands as
-- references ("make one like this"). We store only the pin and its labels and show it through
-- Pinterest's own embed, so it plays from Pinterest, credited to whoever made it -- never as a
-- creator's work on Dalaillama.
CREATE TABLE inspiration_item (
    id         UUID         PRIMARY KEY,
    pin_id     VARCHAR(32)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    industry   VARCHAR(32)  NOT NULL,
    note       VARCHAR(300),
    sort_order INT          NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT inspiration_item_pin_uk UNIQUE (pin_id),
    CONSTRAINT inspiration_item_pin_ck CHECK (pin_id ~ '^[0-9]{6,32}$')
);
CREATE INDEX ix_inspiration_item_industry ON inspiration_item (industry, active, sort_order);
