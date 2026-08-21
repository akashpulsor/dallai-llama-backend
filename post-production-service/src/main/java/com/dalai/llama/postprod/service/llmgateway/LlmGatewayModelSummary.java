package com.dalai.llama.postprod.service.llmgateway;

/** Mirrors llm-gateway's own model_master listing -- lets a caller see every candidate model
 * registered for a capability (lip_sync, tts, voice_clone, foley, music) before picking one via
 * CreatePostProductionJobRequest's model override fields. */
public record LlmGatewayModelSummary(
        String modelId,
        String type,
        String capabilities,
        Integer contextWindow,
        Boolean supportsStreaming
) {
}
