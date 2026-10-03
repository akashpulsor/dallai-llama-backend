-- What the provider actually charged for a usage row, in the wallet's currency, before billing's
-- margin.
--
-- total_cost is what the creator's wallet paid. On its own it cannot answer the ops question "what
-- did this project cost us, and what did we make on it": an LLM row's total already includes the
-- usage margin, and the margin has changed (85% -> 20%), so dividing it back out is a guess.
--
-- Backfill: LLM_GATEWAY rows were billed at the 85% margin until this release, so their raw cost
-- is total / 1.85; every other source is billed at raw cost, so raw = total. Rows written from now
-- on carry the exact figure.
ALTER TABLE usage_records ADD COLUMN raw_cost NUMERIC(15, 4);

UPDATE usage_records
SET raw_cost = CASE WHEN source_type = 'LLM_GATEWAY' THEN ROUND(total_cost / 1.85, 4) ELSE total_cost END;

COMMENT ON COLUMN usage_records.raw_cost IS
    'Provider cost before billing margin, wallet currency. LLM_GATEWAY rows before V23 are backfilled at the 85% margin.';
