
ALTER TABLE campaign_data
ADD COLUMN  initial_message_recording VARCHAR(255);

ALTER TABLE twilio_data
ADD COLUMN  phone_number_sid VARCHAR(50);
