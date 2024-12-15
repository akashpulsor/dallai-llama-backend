ALTER TABLE llm_data ADD active BOOLEAN DEFAULT TRUE;

ALTER TABLE llm_data ADD image_url VARCHAR(255)  DEFAULT  NULL;

ALTER TABLE twilio_data RENAME COLUMN twilio_id TO phone_id;

ALTER TABLE twilio_data ADD vendor_name VARCHAR(255)  DEFAULT  NULL;

CREATE TABLE campaign_run_data
(
    campaign_run_id INT AUTO_INCREMENT NOT NULL,
    business_id     INT                NOT NULL,
    campaign_id     INT                NOT NULL,
    `all`           BIT(1)             NULL,
    agent_id        INT                NOT NULL,
    language        VARCHAR(255)       NULL,
    status          VARCHAR(255)       NULL,
    llm_id          INT                NOT NULL,
    phone_id        INT                NOT NULL,
    created_at      datetime           NULL,
    updated_at      datetime           NULL,
    CONSTRAINT pk_campaign_run_data PRIMARY KEY (campaign_run_id)
);

CREATE TABLE campaign_run_leads
(
    campaign_run_id INT NOT NULL,
    lead_id         INT NULL
);

ALTER TABLE campaign_run_leads
    ADD CONSTRAINT fk_campaign_run_leads_on_campaign_run_data FOREIGN KEY (campaign_run_id) REFERENCES campaign_run_data (campaign_run_id);



