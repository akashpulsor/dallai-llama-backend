CREATE TABLE dalai_llama_lead_data (
    lead_id INT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255),
    name VARCHAR(255),
    phone VARCHAR(50),
    country_code VARCHAR(10),
    country_calling_code VARCHAR(10),
    company_size VARCHAR(50),
    -- Sanitary column fields (assuming standard audit columns)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);