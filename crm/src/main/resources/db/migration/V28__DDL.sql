     CREATE TABLE transcription_data (
         transcription_id INT NOT NULL AUTO_INCREMENT,
         account_sid VARCHAR(255),
         api_version VARCHAR(255),
         call_sid VARCHAR(255),
         conference_sid VARCHAR(255),
         date_created TIMESTAMP,
         date_updated TIMESTAMP,
         start_time TIMESTAMP,
         duration VARCHAR(255),
         sid VARCHAR(255),
         price VARCHAR(255),
         price_unit VARCHAR(255),
         status VARCHAR(255),
         channels INT,
         source VARCHAR(255),
         error_code INT,
         uri VARCHAR(255),
         media_url VARCHAR(255),
         call_id INT,
         campaign_id INT,
         campaign_run_id INT,
         business_id INT,
         phone_id INT,
         llm_id INT,
         lead_id INT,
         inbound_transcription_text TEXT,
         outbound_transcription_text TEXT,
         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
         PRIMARY KEY (transcription_id)
     );

     CREATE INDEX idx_transcription_business_id ON transcription_data (business_id);
     CREATE INDEX idx_transcription_campaign_run_id ON transcription_data (campaign_run_id);
     CREATE INDEX idx_transcription_call_id ON transcription_data (call_id);
