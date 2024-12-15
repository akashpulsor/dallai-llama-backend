ALTER TABLE llm_data RENAME COLUMN image_url TO logo_image;

ALTER TABLE twilio_data ADD logo_image VARCHAR(255)  DEFAULT  NULL;

