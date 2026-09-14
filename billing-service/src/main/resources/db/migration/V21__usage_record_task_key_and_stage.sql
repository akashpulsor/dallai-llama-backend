-- Give a usage row a subject, so a wallet statement can say what the money bought.
--
-- Today every row reads "AI usage: gemini-2.5-flash" with metric=AI_LLM_TOKENS and
-- source_type=LLM_GATEWAY -- 929 of them, identical apart from the amount. A creator reading that
-- learns only that the number went down. There is no way to group it, no way to see which stage of
-- a project is expensive, and a per-project total is the finest breakdown available.
--
-- task_key is what the call was for and comes from llm-gateway (V96 there captures it); stage is
-- this service's grouping of those keys into the phases a creator actually recognises. Both stored:
-- the key is the fact, the stage is our interpretation of it, and keeping the fact means a
-- regrouping later does not need the data regenerated.
ALTER TABLE usage_records ADD COLUMN task_key VARCHAR(64);
ALTER TABLE usage_records ADD COLUMN stage VARCHAR(32);

-- The statement is read per tenant, newest first, and grouped by stage.
CREATE INDEX idx_usage_records_tenant_recorded ON usage_records (tenant_id, recorded_at DESC);
CREATE INDEX idx_usage_records_stage ON usage_records (tenant_id, stage);

-- Existing rows keep NULL rather than being back-filled from the model id: the model says nothing
-- about which stage spent the money, and a guess would be indistinguishable from a fact once
-- written. They report as "Other".
