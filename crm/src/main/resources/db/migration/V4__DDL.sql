CREATE TABLE reset_password_verification_code
(
    verification_code_id INT AUTO_INCREMENT NOT NULL,
    verification_code    INT                NULL,
    user_id              INT                NULL,
    expiry_time          INT                NULL,
    created_at           datetime           NULL,
    updated_at           datetime           NULL,
    CONSTRAINT pk_reset_password_verification_code PRIMARY KEY (verification_code_id)
);