-- A brand's request is the creator's brief form (rule 48): what the video is for, the product, who
-- it's for, the direction, length and languages. All optional except the message (the brief text);
-- "Make a brief" carries them into the creative-planning brief.
ALTER TABLE lead_brand_inquiry
    ADD COLUMN product_name        VARCHAR(160),
    ADD COLUMN product_description VARCHAR(1000),
    ADD COLUMN target_audience     VARCHAR(500),
    ADD COLUMN campaign_direction  VARCHAR(1000),
    ADD COLUMN duration_seconds    INT,
    ADD COLUMN languages           VARCHAR(200);
