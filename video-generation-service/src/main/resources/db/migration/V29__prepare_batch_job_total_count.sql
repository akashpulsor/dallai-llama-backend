-- How many shots a batch will attempt, so the UI can show "4 of 13" instead of an unmoving
-- spinner for the couple of minutes a batch takes.
--
-- Nullable: an empty shotIds means "every shot in the project", which is not resolved until the
-- consumer has the prepare bundle, so the value only arrives once the batch actually starts.
--
-- A separate migration rather than an edit to V28, which had already run in this cluster -- an
-- applied migration is immutable, and changing one only produces the checksum mismatch that
-- refuses to start the service.
ALTER TABLE prepare_batch_job ADD COLUMN total_count INTEGER;
