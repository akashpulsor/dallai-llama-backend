-- Record what a call was FOR, not just which model ran it.
--
-- Every billable LLM call arrives with a task_key -- PRE_PROD_SHOT_LIST_GENERATE,
-- PROMPT_COMPRESSION, FOLEY_CUE_DERIVATION and forty others -- which is used to pick the prompt
-- template and then discarded. So the billing trail that comes out the far end is 929 rows all
-- reading "AI usage: gemini-2.5-flash", and a creator looking at their wallet cannot tell what
-- any of it was spent on.
--
-- The task key is the only thing that knows. Keeping it here lets the billing event carry it, and
-- lets billing group a statement by what the money actually bought.
ALTER TABLE llm_job ADD COLUMN task_key VARCHAR(64);

-- Existing rows stay null: the information was never captured and cannot be recovered from a
-- model id. They group as "Other" downstream rather than being guessed into a category.
CREATE INDEX idx_llm_job_task_key ON llm_job (task_key);
