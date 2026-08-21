-- Plain Postgres array, not pgvector (this cluster's Postgres image doesn't have that extension
-- installed) -- see CritiqueFeedback's own javadoc for the tradeoff.
ALTER TABLE critique_feedback
    ADD COLUMN embedding DOUBLE PRECISION[];
