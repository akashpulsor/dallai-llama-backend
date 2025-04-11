

ALTER TABLE payment_data
ADD COLUMN campaign_id INT,
ADD COLUMN campaign_run_id INT,
ADD COLUMN business_id INT,
ADD COLUMN lead_id INT,
ADD COLUMN agent_id INT,
ADD COLUMN llm_id INT,
ADD COLUMN phone_id INT;

CREATE INDEX idx_campaign_id ON payment_data (campaign_id);
CREATE INDEX idx_campaign_run_id ON payment_data (campaign_run_id);
CREATE INDEX idx_business_id ON payment_data (business_id);
CREATE INDEX idx_lead_id ON payment_data (lead_id);
CREATE INDEX idx_agent_id ON payment_data (agent_id);
CREATE INDEX idx_llm_id ON payment_data (llm_id);
CREATE INDEX idx_phone_id ON payment_data (phone_id);