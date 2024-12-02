CREATE TABLE llm_data
(
    llm_id      INT AUTO_INCREMENT NOT NULL,
    business_id INT                NULL,
    api_key     VARCHAR(255)       NULL,
    vendor_name VARCHAR(255)       NULL,
    created_at  datetime           NULL,
    updated_at  datetime           NULL,
    CONSTRAINT pk_llm_data PRIMARY KEY (llm_id)
);


CREATE TABLE twilio_data
(
    twilio_id          INT AUTO_INCREMENT NOT NULL,
    business_id        INT                NULL,
    account_auth_token VARCHAR(255)       NULL,
    account_sid        VARCHAR(255)       NULL,
    business_number    VARCHAR(255)       NULL,
    friendly_name      VARCHAR(255)       NULL,
    call_secret        VARCHAR(255)       NULL,
    status             VARCHAR(255)       NULL,
    active             BIT(1)             NULL,
    created_at         datetime           NULL,
    updated_at         datetime           NULL,
    UNIQUE KEY unique_business_id (business_id),
    UNIQUE KEY unique_account_sid (account_sid),
    CONSTRAINT pk_twilio_data PRIMARY KEY (twilio_id)
);