CREATE TABLE business_classification
(
    id         INT AUTO_INCREMENT NOT NULL,
    criteria   VARCHAR(255) NULL,
    country_id INT NULL,
    CONSTRAINT pk_business_classification PRIMARY KEY (id)
);

CREATE TABLE business_data
(
    business_id                INT AUTO_INCREMENT NOT NULL,
    email                      VARCHAR(255) NULL,
    mobile                     VARCHAR(255) NULL,
    whatsapp_number            VARCHAR(255) NULL,
    business_name              VARCHAR(255) NULL,
    country_id                 INT NULL,
    number_of_employee         INT NULL,
    basic_activity_description VARCHAR(255) NULL,
    CONSTRAINT pk_business_data PRIMARY KEY (business_id)
);

CREATE TABLE business_data_india
(
    business_id               INT AUTO_INCREMENT NOT NULL,
    parent_business_id        INT NULL,
    udyam_registration_number VARCHAR(255) NULL,
    pan                       VARCHAR(255) NULL,
    adhaar_number             VARCHAR(255) NULL,
    country_id                INT NULL,
    company_type_id           INT NULL,
    active                    BIT(1) NULL,
    gst_in                    VARCHAR(255) NULL,
    street                    VARCHAR(255) NULL,
    city                      VARCHAR(255) NULL,
    state                     VARCHAR(255) NULL,
    zip_code                  VARCHAR(255) NULL,
    bank_account_number       VARCHAR(255) NULL,
    bank_name                 VARCHAR(255) NULL,
    bank_branch               VARCHAR(255) NULL,
    ifsc_code                 VARCHAR(255) NULL,
    CONSTRAINT pk_business_data_india PRIMARY KEY (business_id)
);

CREATE TABLE company_types
(
    company_type_id            INT AUTO_INCREMENT NOT NULL,
    company_type_name          VARCHAR(255) NULL,
    business_classification_id INT NULL,
    CONSTRAINT pk_company_types PRIMARY KEY (company_type_id)
);

CREATE TABLE country_master_data
(
    id         INT AUTO_INCREMENT NOT NULL,
    name       VARCHAR(255) NULL,
    code       VARCHAR(255) NULL,
    phone_code VARCHAR(255) NULL,
    image_url  VARCHAR(255) NULL,
    CONSTRAINT pk_country_master_data PRIMARY KEY (id)
);