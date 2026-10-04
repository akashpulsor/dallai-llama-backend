-- What the client says they want to spend on the video. The client is asked for a budget instead
-- of being shown a price computed from a per-second rate; the creator reads it and sets the price
-- (quoted_total_price) themselves.
ALTER TABLE project_requirement
    ADD COLUMN client_budget          NUMERIC(12, 2),
    ADD COLUMN client_budget_currency VARCHAR(3);
