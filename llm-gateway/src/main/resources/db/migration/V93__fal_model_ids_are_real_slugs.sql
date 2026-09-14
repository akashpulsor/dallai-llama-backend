-- For a fal.ai model, model_id IS the request path. Make that true of every fal model, not some.
--
-- FalAiProvider.falEndpoint() builds the URL as modelId (+ "/image-to-video" for video), so
-- 'alibaba/wan-3.0-prime' dispatches and 'seedance-v1' POSTs to fal.run/seedance-v1/image-to-video
-- and 404s. Half the fal rows in model_master are real slugs and half are invented names that have
-- never been able to run. Nothing distinguishes the two, so a model can be seeded, priced, listed
-- in the UI and selected by a creator while being incapable of generating anything.
--
-- post-production-service's config already carries the scars: "Was foley-v1 -- not a real fal.ai
-- app at all; every dispatch against it would fail outright". That fix changed which model the
-- config pointed at and left the dead rows behind, still active, still selectable.

-- 1. seedance-v1 -> its real slug. fal lists this endpoint at
--    fal.ai/models/bytedance/seedance-2.0/fast/image-to-video, and falEndpoint() appends the
--    /image-to-video part, so the app slug is what belongs here. Matches what FalAiProvider
--    already documents about this model: 480p/720p, default 720p, no 1080p.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window,
                          supports_streaming, status, default_rpm, default_tpm, timeout_ms,
                          created_at, updated_at)
SELECT 'bytedance/seedance-2.0/fast', provider_id, type, capabilities, context_window,
       supports_streaming, status, default_rpm, default_tpm, timeout_ms, now(), now()
FROM model_master WHERE model_id = 'seedance-v1'
ON CONFLICT (model_id) DO NOTHING;

-- Repoint every child row. Done explicitly rather than with ON UPDATE CASCADE because the FKs
-- were not declared with it, and a rename that silently dropped pricing or job history would be
-- worse than one that fails.
UPDATE rate_card                SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';
UPDATE tenant_model_override    SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';
UPDATE llm_job                  SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';
UPDATE model_supported_language SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';
UPDATE model_capability         SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';
UPDATE provider_language_mapping SET model_id = 'bytedance/seedance-2.0/fast' WHERE model_id = 'seedance-v1';

DELETE FROM model_master WHERE model_id = 'seedance-v1';

-- 2. Every other fal row whose id is not a real slug is deactivated, not renamed. Renaming needs
--    the true slug for each, and inventing one is how seedance came to be priced 11x under cost.
--    Deactivated is the honest state: ModelRouterService already refuses a non-active model with a
--    clear error, so selecting one now fails at routing with a message instead of as an opaque 404
--    from fal after the job row is claimed.
--
--    To bring one back: confirm its slug on fal.ai, rename it the way seedance is renamed above,
--    price its tiers, then set status='active'.
UPDATE model_master
SET status = 'inactive', updated_at = now()
WHERE provider_id = 'fal.ai'
  AND status = 'active'
  AND model_id NOT LIKE '%/%';
