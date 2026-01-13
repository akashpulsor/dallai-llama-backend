CREATE TABLE plans (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    tier VARCHAR(20) NOT NULL, -- STARTER, PROFESSIONAL, ENTERPRISE, CUSTOM
    monthly_price DECIMAL(12,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',
    is_default BOOLEAN DEFAULT false,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_plan_product ON plans(product_id);
CREATE INDEX idx_plan_tier ON plans(tier);