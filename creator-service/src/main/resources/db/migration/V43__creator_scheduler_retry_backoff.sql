ALTER TABLE creator_source_connectors
    ALTER COLUMN retry_backoff_ms SET DEFAULT 60000;

UPDATE creator_source_connectors
   SET retry_backoff_ms = 60000,
       updated_at = now()
 WHERE retry_count > 0
   AND retry_backoff_ms < 60000;

COMMENT ON COLUMN creator_source_connectors.retry_backoff_ms IS 'Base delay between retries in milliseconds. Scheduler applies linear backoff per failed attempt.';
