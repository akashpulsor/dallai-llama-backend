ALTER TABLE project_requirement
    ADD COLUMN duration_seconds INT,
    ADD COLUMN languages TEXT,
    ADD COLUMN quoted_platform_cost NUMERIC(12, 2),
    ADD COLUMN quoted_creator_margin_percent NUMERIC(5, 2),
    ADD COLUMN quoted_total_price NUMERIC(12, 2),
    ADD COLUMN quoted_currency VARCHAR(3);
