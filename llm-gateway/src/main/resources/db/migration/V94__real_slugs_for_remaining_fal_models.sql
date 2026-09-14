-- Gives the remaining fal placeholders their real slugs, and settles which ones exist at all.
--
-- V93 deactivated every fal row whose id was not a real slug rather than invent names for them.
-- These are the ones now checked against fal.ai's catalogue (2026-09-14) and resolved.
--
-- Two of them were live defaults in post-production-service and therefore broken in production:
-- default-transcription-model pointed at 'transcription-v1' and default-video-edit-model at
-- 'kling-edit-v1', neither of which fal has ever served.

-- Rename helper pattern is the same as V93's: copy the row under the real slug, repoint every
-- child table, drop the old row. The FKs have no ON UPDATE CASCADE, and silently losing pricing or
-- job history to a rename would be worse than failing.

-- 1. transcription-v1 -> fal-ai/wizper  ("Whisper v3 Large, optimized" -- fal's own edition).
--    $0.0008 per second of audio.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window,
                          supports_streaming, status, default_rpm, default_tpm, timeout_ms,
                          created_at, updated_at)
SELECT 'fal-ai/wizper', provider_id, type, capabilities, context_window, supports_streaming,
       'active', default_rpm, default_tpm, timeout_ms, now(), now()
FROM model_master WHERE model_id = 'transcription-v1'
ON CONFLICT (model_id) DO NOTHING;

UPDATE rate_card                 SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
UPDATE tenant_model_override     SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
UPDATE llm_job                   SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
UPDATE model_supported_language  SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
UPDATE model_capability          SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
UPDATE provider_language_mapping SET model_id = 'fal-ai/wizper' WHERE model_id = 'transcription-v1';
DELETE FROM model_master WHERE model_id = 'transcription-v1';

UPDATE rate_card SET per_second_cost = 0.0008000000 WHERE model_id = 'fal-ai/wizper';

-- 2. kling-edit-v1 -> fal-ai/kling-video/o1/video-to-video/edit. $0.168 per second.
--    falEndpoint() appends /image-to-video only for type=video; video_edit is returned as-is, so
--    the full path belongs in model_id.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window,
                          supports_streaming, status, default_rpm, default_tpm, timeout_ms,
                          created_at, updated_at)
SELECT 'fal-ai/kling-video/o1/video-to-video/edit', provider_id, type, capabilities, context_window,
       supports_streaming, 'active', default_rpm, default_tpm, timeout_ms, now(), now()
FROM model_master WHERE model_id = 'kling-edit-v1'
ON CONFLICT (model_id) DO NOTHING;

UPDATE rate_card                 SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
UPDATE tenant_model_override     SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
UPDATE llm_job                   SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
UPDATE model_supported_language  SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
UPDATE model_capability          SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
UPDATE provider_language_mapping SET model_id = 'fal-ai/kling-video/o1/video-to-video/edit' WHERE model_id = 'kling-edit-v1';
DELETE FROM model_master WHERE model_id = 'kling-edit-v1';

UPDATE rate_card SET per_second_cost = 0.1680000000 WHERE model_id = 'fal-ai/kling-video/o1/video-to-video/edit';

-- 3. Real slugs, but left inactive: nothing selects them, and activating a model whose pricing
--    shape rate_card cannot express (flux-pulid bills per megapixel, not per second or token)
--    would put an unpriceable model in front of a creator. Renamed anyway so the id is true and
--    whoever needs one is renaming nothing, only pricing it and flipping status.
--    Renamed with the same copy/repoint/delete as above, not a bare UPDATE of model_master's
--    primary key: rate_card (and every other child) holds an FK to it with no ON UPDATE CASCADE,
--    so a straight PK update is refused outright.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window,
                          supports_streaming, status, default_rpm, default_tpm, timeout_ms,
                          created_at, updated_at)
SELECT 'fal-ai/flux-pulid', provider_id, type, capabilities, context_window, supports_streaming,
       status, default_rpm, default_tpm, timeout_ms, now(), now()
FROM model_master WHERE model_id = 'flux-pulid-v1'
ON CONFLICT (model_id) DO NOTHING;

UPDATE rate_card                 SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
UPDATE tenant_model_override     SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
UPDATE llm_job                   SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
UPDATE model_supported_language  SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
UPDATE model_capability          SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
UPDATE provider_language_mapping SET model_id = 'fal-ai/flux-pulid' WHERE model_id = 'flux-pulid-v1';
DELETE FROM model_master WHERE model_id = 'flux-pulid-v1';

INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window,
                          supports_streaming, status, default_rpm, default_tpm, timeout_ms,
                          created_at, updated_at)
SELECT 'fal-ai/musetalk', provider_id, type, capabilities, context_window, supports_streaming,
       status, default_rpm, default_tpm, timeout_ms, now(), now()
FROM model_master WHERE model_id = 'musetalk-v1'
ON CONFLICT (model_id) DO NOTHING;

UPDATE rate_card                 SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
UPDATE tenant_model_override     SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
UPDATE llm_job                   SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
UPDATE model_supported_language  SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
UPDATE model_capability          SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
UPDATE provider_language_mapping SET model_id = 'fal-ai/musetalk' WHERE model_id = 'musetalk-v1';
DELETE FROM model_master WHERE model_id = 'musetalk-v1';

-- 4. Left deactivated for good: each is superseded by a real model already seeded here, so giving
--    them slugs would only create a second way to reach the same thing.
--      sync-so-v1, wav2lip-v1, runway-act-two-v1 -> fal-ai/sync-lipsync (active, and what
--                                                   post-production actually uses)
--      voice-clone-v1                            -> fal-ai/minimax/voice-clone
--      tts-v1                                    -> elevenlabs-tts-v1 (the configured default)
--      music-gen-v1                              -> elevenlabs/music-v1
--      foley-v1                                  -> beatoven/sound-effect-generation
--    They keep their placeholder ids deliberately: an id that was never real should not be
--    dressed up to look like it was.
