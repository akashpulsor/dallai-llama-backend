-- Append-only: never UPDATE a row here. A price change is a new row with a new
-- effective_from; cost computation always uses the version active at the request's
-- created_at, so historical bills stay reproducible.
CREATE TABLE rate_card (
    rate_card_id       BIGSERIAL PRIMARY KEY,
    model_id           VARCHAR(128) NOT NULL REFERENCES model_master(model_id),
    input_token_cost   NUMERIC(18, 10) NOT NULL,
    output_token_cost  NUMERIC(18, 10) NOT NULL,
    currency           VARCHAR(8) NOT NULL DEFAULT 'USD',
    effective_from     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rate_card_model_effective ON rate_card (model_id, effective_from DESC);
