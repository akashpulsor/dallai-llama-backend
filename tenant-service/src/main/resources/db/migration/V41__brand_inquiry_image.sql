-- Product and reference images a brand attaches to a request (rule 48). Small (3 MB each, 4 of each
-- kind), so kept with the request; they go to creative-planning when the creator makes the brief.
CREATE TABLE lead_brand_inquiry_image (
    id            UUID          PRIMARY KEY,
    inquiry_id    UUID          NOT NULL,
    kind          VARCHAR(16)   NOT NULL,
    file_name     VARCHAR(160),
    content_type  VARCHAR(64)   NOT NULL,
    bytes         BYTEA         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT lead_brand_inquiry_image_kind_ck CHECK (kind IN ('PRODUCT', 'REFERENCE')),
    CONSTRAINT lead_brand_inquiry_image_inquiry_fk FOREIGN KEY (inquiry_id) REFERENCES lead_brand_inquiry (id) ON DELETE CASCADE
);

CREATE INDEX ix_lead_brand_inquiry_image_inquiry ON lead_brand_inquiry_image (inquiry_id);
