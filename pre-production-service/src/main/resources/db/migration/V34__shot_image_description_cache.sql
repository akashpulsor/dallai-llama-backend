-- Caches ShotImageDescriptionService's vision-model caption per image so re-locking a project (or
-- a chat backfill across many projects) never re-pays for the same image twice -- describing is a
-- real LLM call, not a free lookup.
ALTER TABLE shot_image ADD COLUMN description TEXT;
