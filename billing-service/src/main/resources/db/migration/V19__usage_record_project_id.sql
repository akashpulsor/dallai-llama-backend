-- Lets a project's real accumulated AI-generation cost be summed straight from the existing
-- append-only usage ledger (usage_records), rather than a second running-total table that would
-- need to stay transactionally consistent with it. Null for usage not tied to a project (call
-- minutes, subscription fees, etc.) -- only llm-gateway-sourced LLM/generation usage populates it.
ALTER TABLE usage_records ADD COLUMN project_id UUID;

CREATE INDEX idx_usage_records_project_id ON usage_records (project_id) WHERE project_id IS NOT NULL;
