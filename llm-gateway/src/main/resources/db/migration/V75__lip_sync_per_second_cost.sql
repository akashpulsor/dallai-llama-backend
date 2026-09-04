-- fal-ai/sync-lipsync: $0.70/minute = $0.011667/second, verified against fal.ai's real published
-- model page. LlmGatewayService.computeCost's duration-priced branch now includes type=lip_sync;
-- LlmGatewayLipSyncGenerationService now sends duration_seconds (real, when the caller has it --
-- currently a documented DEFAULT_SHOT_DURATION_SECONDS=4 approximation for the automatic
-- DialogueSyncCoordinator/DubbingOrchestrator pipelines, which don't yet track a shot's real
-- duration; see that class's own comment).
UPDATE rate_card SET per_second_cost = 0.011667 WHERE model_id = 'fal-ai/sync-lipsync';
