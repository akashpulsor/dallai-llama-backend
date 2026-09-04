-- Source-of-truth pointers back to pre-production-service's rows that produced the composed
-- prompt in this row. Rest of the shot context (camera notes, lighting mood, cast, dialogue
-- beats, ...) is NOT duplicated here -- pre-prod owns it, and duplicating would just create
-- drift. With these ids on the row, debugging a bad prompt is: "here's the composed text ->
-- follow these ids -> read the exact pre-prod rows that fed it". Every id nullable because a
-- shot without a lighting plan (or a product reference, or a bg-music pick) is still valid to
-- prepare a prompt for.
--
-- recommended_model_id captures whatever llm-gateway's recommender picked at prepare time (when
-- the recommender_enabled feature-flag on ProjectConfig was on), so the UI can render a
-- "Recommended: X" chip on the shot card even after the user pins a different model. Different
-- from shot_prompt's runtime modelId used for dispatch -- this is the suggestion, not the pick.
--
-- prompt_bundle_snapshot_at records the pre-prod bundle timestamp we composed against, so a
-- reader can tell whether a shot prompt still reflects the current pre-prod state or was built
-- before a subsequent shot/plan/image change.
ALTER TABLE shot_prompt
    ADD COLUMN shot_id                       UUID,
    ADD COLUMN camera_plan_id                UUID,
    ADD COLUMN lighting_plan_id              UUID,
    ADD COLUMN product_reference_id          UUID,
    ADD COLUMN background_music_id           UUID,
    ADD COLUMN recommended_model_id          VARCHAR(128),
    ADD COLUMN prompt_bundle_snapshot_at     TIMESTAMPTZ;

-- "Which prompt did we build for this shot" -- the audit-drawer query in the UI.
CREATE INDEX idx_shot_prompt_shot_id ON shot_prompt (shot_id);
