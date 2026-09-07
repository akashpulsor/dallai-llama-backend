-- MiniMax voice-clone is being retired in favor of ElevenLabs-only cloning + a built-in ElevenLabs
-- voice catalog (see V80). Deactivating via the existing status flag rather than deleting the row:
-- ModelRouterService already excludes non-active models from GET /v1/models (the frontend dropdown)
-- and 400s any direct dispatch attempt, so this alone fully retires it with no code change and stays
-- reversible (flip status back to 'active') if MiniMax is ever needed again.
UPDATE model_master SET status = 'inactive' WHERE model_id = 'fal-ai/minimax/voice-clone';
