-- Account-level concurrency cap: the RPM token bucket (rate_limiter) controls admission rate,
-- not how many requests are simultaneously blocked inside a provider call. Long-running video
-- generation calls (minutes, not seconds) need a separate concurrency gate so the token bucket
-- can't admit more simultaneously in-flight provider calls than the provider account can handle.
ALTER TABLE provider ADD COLUMN max_concurrent INTEGER;
ALTER TABLE tenant_model_override ADD COLUMN max_concurrent_override INTEGER;

-- fal.ai (Seedance) is the only provider today with a genuinely long-blocking call path
-- (submit-then-poll, can run minutes) -- cap it conservatively; adjust once real traffic is seen.
UPDATE provider SET max_concurrent = 3 WHERE provider_id = 'fal.ai';
UPDATE provider SET max_concurrent = 20 WHERE provider_id = 'google';
