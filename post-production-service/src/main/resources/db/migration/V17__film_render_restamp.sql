-- The stamp changed (company logo with www.dalaillama.in, top-right): stamp every film again. The
-- sweep picks them up and overwrites the same stamped object key.
UPDATE film_render SET stamped_object_key = NULL, stamp_status = NULL, stamp_error = NULL, stamp_attempts = 0
WHERE stamped_object_key IS NOT NULL OR stamp_status IS NOT NULL;
