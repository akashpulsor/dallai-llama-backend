-- WHEN A JOIN ACTUALLY BEGAN, as opposed to when it was asked for.
--
-- Films are joined one at a time: every tenant's request goes onto one Kafka topic and is consumed
-- serially, because a join is an ffmpeg run sized to the whole container's memory rather than a
-- message. That is the right behaviour, but it means a film can sit QUEUED behind several others,
-- and the page had no way to say so -- an unexplained spinner reads as "broken" long before it reads
-- as "third in line".
--
-- created_at alone cannot answer it. completed_at - created_at is queue wait PLUS encode time, so
-- using it to estimate the encode makes every estimate longer the busier things are, which is
-- precisely backwards. started_at splits the two so the estimate is built from how long joins
-- actually take.
ALTER TABLE film_render
    ADD COLUMN started_at TIMESTAMPTZ;

-- "What is still waiting, oldest first" -- the queue read, run on every poll of the film panel.
CREATE INDEX idx_film_render_queued
    ON film_render (created_at) WHERE status = 'QUEUED';
