package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Same fal.ai meta-provider dispatch principle and the same named model_master/FalAiProvider
 * gap as {@link LlmGatewayVoiceCloneGenerationService} -- see its class comment. Default model is
 * Wav2Lip (per the explicit ask to include it among lip-sync options); Sync.so/Runway Act-Two/
 * MuseTalk register as additional model_master rows behind the same adapter later, same pattern
 * as adding a second video model beyond Seedance. */
@Service
public class LlmGatewayLipSyncGenerationService implements LipSyncGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayLipSyncGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-lip-sync-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    /** Same convention ShotListGenerationService.DEFAULT_SHOT_DURATION_SECONDS uses when a shot
     * has no explicit duration -- reused here as a documented billing approximation, not measured
     * per-call, until the automatic dialogue-sync pipeline threads a shot's real duration through
     * (it doesn't today; see LipSyncGenerationService's javadoc). */
    private static final double DEFAULT_SHOT_DURATION_SECONDS = 4.0;

    @Override
    public LipSyncResult syncLips(UUID tenantId, String idempotencyKey, String sourceVideoUrl, String dialogueAudioUrl, String modelOverride, Double durationSeconds) {
        if (sourceVideoUrl == null || sourceVideoUrl.isBlank()) {
            throw PostProductionException.badRequest("No source video to lip-sync");
        }
        if (dialogueAudioUrl == null || dialogueAudioUrl.isBlank()) {
            throw PostProductionException.badRequest("No dialogue audio to lip-sync the video to");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source_video_url", sourceVideoUrl);
        params.put("dialogue_audio_url", dialogueAudioUrl);
        params.put("duration_seconds", durationSeconds != null ? durationSeconds : DEFAULT_SHOT_DURATION_SECONDS);

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", "")), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no lip-synced output URI");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        return new LipSyncResult(response.jobId(), response.response(), cost);
    }
}
