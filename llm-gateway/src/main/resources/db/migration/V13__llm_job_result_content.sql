-- v1's idempotent-replay path (handleExisting) previously returned content=null for an already-
-- COMPLETED job -- fine for "don't double-dispatch/double-charge," not fine for a caller trying
-- to recover a result it never received the first time (e.g. its own pod crashed mid-request).
-- Persisting the result lets a legitimate replay actually recover the output, not just confirm
-- billing already happened.
ALTER TABLE llm_job ADD COLUMN result_content TEXT;
