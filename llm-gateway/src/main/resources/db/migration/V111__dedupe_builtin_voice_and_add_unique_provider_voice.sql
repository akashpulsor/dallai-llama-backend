-- Dedupes builtin_voice rows sharing the same (provider_id, provider_voice_id) natural key and
-- adds a unique constraint to prevent it happening again.
--
-- Root cause: an early BuiltinVoiceSyncService run (before the WebFlux buffer bump that made
-- the sync actually finish) inserted duplicate rows for the four V80-seeded voices -- friendly
-- rows like ('elevenlabs-sarah', 'EXAVITQu4vr4xnSDxMaL') plus auto-generated rows like
-- ('elevenlabs-EXAVITQu4vr4xnSDxMaL', 'EXAVITQu4vr4xnSDxMaL'). The service's later
-- findByProviderIdAndProviderVoiceId lookup then threw IncorrectResultSize and 500'd every
-- subsequent sync attempt.
--
-- Winner-selection: keep the row with the shortest voice_id (V80's friendly names --
-- "elevenlabs-sarah" is shorter than "elevenlabs-EXAVITQu4vr4xnSDxMaL"). Matches
-- BuiltinVoiceSyncService's own defensive tie-breaker so code and migration agree.
--
-- FK note: builtin_voice_language references builtin_voice(voice_id). Move language mappings
-- from the losing rows to the winning row before deleting, avoiding orphan FKs. INSERT
-- ON CONFLICT DO NOTHING handles the case where a language pair already exists on the winner.

WITH ranked AS (
    SELECT voice_id, provider_id, provider_voice_id,
           ROW_NUMBER() OVER (
               PARTITION BY provider_id, provider_voice_id
               ORDER BY LENGTH(voice_id) ASC, voice_id ASC
           ) AS rn
    FROM builtin_voice
),
winners AS (
    SELECT provider_id, provider_voice_id, voice_id
    FROM ranked WHERE rn = 1
),
losers AS (
    SELECT r.voice_id AS loser_voice_id, w.voice_id AS winner_voice_id
    FROM ranked r
    JOIN winners w ON w.provider_id = r.provider_id AND w.provider_voice_id = r.provider_voice_id
    WHERE r.rn > 1
)
INSERT INTO builtin_voice_language (voice_id, language_code)
SELECT winner_voice_id, language_code
FROM losers l
JOIN builtin_voice_language bvl ON bvl.voice_id = l.loser_voice_id
ON CONFLICT DO NOTHING;

DELETE FROM builtin_voice_language
WHERE voice_id IN (
    SELECT r.voice_id
    FROM (
        SELECT voice_id,
               ROW_NUMBER() OVER (
                   PARTITION BY provider_id, provider_voice_id
                   ORDER BY LENGTH(voice_id) ASC, voice_id ASC
               ) AS rn
        FROM builtin_voice
    ) r
    WHERE r.rn > 1
);

DELETE FROM builtin_voice
WHERE voice_id IN (
    SELECT r.voice_id
    FROM (
        SELECT voice_id,
               ROW_NUMBER() OVER (
                   PARTITION BY provider_id, provider_voice_id
                   ORDER BY LENGTH(voice_id) ASC, voice_id ASC
               ) AS rn
        FROM builtin_voice
    ) r
    WHERE r.rn > 1
);

ALTER TABLE builtin_voice
    ADD CONSTRAINT ux_builtin_voice_provider_voice_id UNIQUE (provider_id, provider_voice_id);
