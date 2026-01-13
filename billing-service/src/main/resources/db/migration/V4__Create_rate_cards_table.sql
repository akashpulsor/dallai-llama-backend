CREATE TABLE rate_cards (
    id UUID PRIMARY KEY,
    rate_plan_id UUID NOT NULL REFERENCES rate_plans(id),

    metric VARCHAR(50) NOT NULL,
    destination_prefix VARCHAR(20),
    destination_type VARCHAR(30),

    rate_per_unit DECIMAL(15,6) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    billing_increment INT DEFAULT 60,
    minimum_charge INT DEFAULT 0,

    effective_from TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    effective_to TIMESTAMP WITH TIME ZONE,
    priority INT DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_rate_card_plan ON rate_cards(rate_plan_id);
CREATE INDEX idx_rate_card_metric ON rate_cards(metric);
CREATE INDEX idx_rate_card_prefix ON rate_cards(destination_prefix);