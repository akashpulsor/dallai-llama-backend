CREATE TABLE campaign_data
(
    campaign_id              INT AUTO_INCREMENT NOT NULL,
    business_id              INT                NULL,
    campaign_aim             VARCHAR(255)       NULL,
    campaign_desc            VARCHAR(255)       NULL,
    campaign_img_url         VARCHAR(255)       NULL,
    campaign_name            VARCHAR(255)       NULL,
    campaign_prompt          VARCHAR(255)       NULL,
    conversation_guide_lines VARCHAR(255)       NULL,
    first_message            VARCHAR(255)       NULL,
    handling_faq             VARCHAR(255)       NULL,
    placing_order            VARCHAR(255)       NULL,
    is_active                BIT(1)             NULL,
    language                 VARCHAR(255)       NULL,
    duration                 INT                NULL,
    created_at               datetime           DEFAULT CURRENT_TIMESTAMP,
    updated_at               datetime           NULL,
    CONSTRAINT pk_campaign_data PRIMARY KEY (campaign_id)
);