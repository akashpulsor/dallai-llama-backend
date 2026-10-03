-- Creative direction generation becomes an asynchronous job of 5-10 treatments.
--
-- Writing up to ten full director's treatments in one model call runs past the 90-second gateway
-- timeout on /v1/projects, so a generation round is now submitted to llm-gateway over Kafka
-- (llm.job.requested) and completed by ChatJobCompletedConsumer -- the same path shot-list
-- generation uses. The round row itself is the job: PENDING until the treatments are stored,
-- then COMPLETED or FAILED with the reason. Rounds written before this (synchronously) are
-- COMPLETED rounds of three.
ALTER TABLE creative_direction_generation ADD COLUMN requested_count INT NOT NULL DEFAULT 3;
ALTER TABLE creative_direction_generation ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE creative_direction_generation ADD COLUMN error_message TEXT;
ALTER TABLE creative_direction_generation ADD COLUMN llm_idempotency_key VARCHAR(255);
ALTER TABLE creative_direction_generation ADD COLUMN completed_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_creative_direction_generation_key ON creative_direction_generation (llm_idempotency_key)
    WHERE llm_idempotency_key IS NOT NULL;
-- One round in flight per project: a second press while one is running returns the running one.
CREATE UNIQUE INDEX uq_creative_direction_generation_pending ON creative_direction_generation (project_id)
    WHERE status = 'PENDING';
