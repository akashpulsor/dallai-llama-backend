CREATE TABLE lead_data
(
    lead_id               INT AUTO_INCREMENT NOT NULL,
    gender                VARCHAR(255)       NULL,
    lead_email            VARCHAR(255)       NULL,
    lead_name             VARCHAR(255)       NULL,
    lead_phone            VARCHAR(255)       NULL,
    phone_country_code    VARCHAR(255)       NULL,
    lead_whatsapp         VARCHAR(255)       NULL,
    whatsapp_country_code VARCHAR(255)       NULL,
    test                  BIT(1)             NULL,
    street                VARCHAR(255)       NULL,
    apartment             VARCHAR(255)       NULL,
    city                  VARCHAR(255)       NULL,
    state                 VARCHAR(255)       NULL,
    zip_code              VARCHAR(255)       NULL,
    country_code          VARCHAR(255)       NULL,
    formatted_address     VARCHAR(255)       NULL,
    created_at            datetime           DEFAULT CURRENT_TIMESTAMP,
    updated_at            datetime           NULL,
    CONSTRAINT pk_lead_data PRIMARY KEY (lead_id)
);

CREATE TABLE business_lead (
                                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                       lead_id INT NOT NULL,
                                       business_id INT NOT NULL,
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                       updated_at datetime NULL,
                                       FOREIGN KEY (lead_id) REFERENCES lead_data(lead_id),
                                       FOREIGN KEY (business_id) REFERENCES business_data(business_id),
                                       UNIQUE KEY unique_lead_business (lead_id, business_id)
);

CREATE INDEX idx_business_id ON business_lead(business_id);
CREATE INDEX idx_lead_id ON business_lead(lead_id);