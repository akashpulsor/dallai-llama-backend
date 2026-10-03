-- Re-recording a rejected take upserts the same shot/beat resource. Before V38 the upsert replaced
-- the audio object and advanced updated_at but left rejected=true, so the creator could play the
-- new audio while every mix path (which correctly ignores rejected takes) saw no dubbed audio.
--
-- Heal only rows that were updated after they were rejected. A genuinely rejected current take has
-- updated_at <= rejected_at and stays rejected; a regenerated one has a newer updated_at.
UPDATE cloned_voice_audio
SET rejected = false,
    rejected_at = NULL
WHERE rejected = true
  AND rejected_at IS NOT NULL
  AND updated_at > rejected_at;
