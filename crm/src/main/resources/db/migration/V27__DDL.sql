CREATE TABLE charges_data (
    cost_id SERIAL PRIMARY KEY,
    call_id INT NOT NULL,
    campaign_run_id INT NOT NULL,
    campaign_id INT NOT NULL,
    model_name VARCHAR(255),
    business_id INT NOT NULL,
    lead_id INT NOT NULL,
    agent_id INT NOT NULL,
    llm_id INT NOT NULL,
    phone_id INT NOT NULL,
    call_duration INT,
    call_last_status INT,
    call_start_time TIMESTAMP,
    call_end_time TIMESTAMP,
    carrier_id INT,
    carrier_charges DECIMAL(10,2),
    model_charges DECIMAL(10,2),
    service_charges DECIMAL(10,2),
    total_charges DECIMAL(10,2),
    input_text_cost DECIMAL(10,4),
    output_text_cost DECIMAL(10,4),
    input_audio_cost DECIMAL(10,4),
    output_audio_cost DECIMAL(10,4),
    input_text_cached_cost DECIMAL(10,4),
    input_audio_cached_cost DECIMAL(10,4),
    total_cost DECIMAL(10,2),
    effective_cost DECIMAL(10,2)
);

-- Indexes for foreign keys and frequent queries
CREATE INDEX idx_charges_business_id ON charges_data (business_id);
CREATE INDEX idx_charges_campaign_id ON charges_data (campaign_id);
CREATE INDEX idx_charges_campaign_run_id ON charges_data (campaign_run_id);
CREATE INDEX idx_charges_call_id ON charges_data (call_id);
CREATE INDEX idx_charges_lead_id ON charges_data (lead_id);
CREATE INDEX idx_charges_agent_id ON charges_data (agent_id);
CREATE INDEX idx_charges_llm_id ON charges_data (llm_id);
CREATE INDEX idx_charges_phone_id ON charges_data (phone_id);

-- Index for date range queries
CREATE INDEX idx_charges_call_times ON charges_data (call_start_time, call_end_time);

-- Index for model cost analysis
CREATE INDEX idx_charges_model ON charges_data (model_name, model_charges);