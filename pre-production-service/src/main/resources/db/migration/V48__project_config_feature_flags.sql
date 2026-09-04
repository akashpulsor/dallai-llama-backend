-- Per-project feature-flag gates for the video-workspace UI's optional enrichments. Each
-- feature has real cost (extra llm-gateway calls, extra UI complexity), so flip them on per
-- project rather than defaulting them all on globally.
--   * recommender_enabled                -- backend calls llm-gateway for a model recommendation
--                                           per shot during prepare, stores it on shot_prompt
--   * cost_preview_enabled               -- backend runs UsageService estimate for aux calls
--                                           (bg music, foley, phoneme guide) so UI can show
--                                           per-shot cost before dispatch
--   * auto_clone_audio_prompt_enabled    -- UI prompts the user to clone a voice sample when
--                                           they pick "cloned audio" but no clone exists yet
--   * price_delta_modal_enabled          -- UI shows a "current vs new pricing" confirmation
--                                           modal when the user changes a shot's model
ALTER TABLE project_config
    ADD COLUMN recommender_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN cost_preview_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN auto_clone_audio_prompt_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN price_delta_modal_enabled      BOOLEAN NOT NULL DEFAULT TRUE;
