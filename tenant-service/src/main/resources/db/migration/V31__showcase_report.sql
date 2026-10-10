-- Creator Showcase, Phase A slice 6: brands can report a video (CREATOR_SHOWCASE.md rule 18).
-- One report per visitor per item; enough distinct reports hide the item until ops looks at it.
CREATE TABLE showcase_report (
    id               UUID         PRIMARY KEY,
    showcase_item_id UUID         NOT NULL,
    visitor_id       UUID         NOT NULL,
    reason           VARCHAR(24)  NOT NULL,
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT showcase_report_visitor_uk UNIQUE (showcase_item_id, visitor_id),
    CONSTRAINT showcase_report_item_fk FOREIGN KEY (showcase_item_id)
        REFERENCES showcase_item (id) ON DELETE CASCADE,
    CONSTRAINT showcase_report_reason_ck CHECK (reason IN ('RIGHTS', 'OFFENSIVE', 'MISLEADING', 'OTHER'))
);

ALTER TABLE showcase_item ADD COLUMN report_count INT NOT NULL DEFAULT 0;
