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

/**
 * Dispatches through llm-gateway's fal.ai meta-provider adapter to fal-ai/minimax/voice-clone --
 * verified against fal.ai's real, documented schema (not a guess): {@code audio_url} in, {@code
 * custom_voice_id} out when no {@code text} is given (a bare clone, this call's case). ElevenLabs
 * does not expose a dedicated instant-voice-clone endpoint on fal.ai (only TTS/voice-changer/
 * dubbing/music/scribe) -- MiniMax is fal.ai's real cloning model, per explicit direction not to
 * stand up a separate direct-to-ElevenLabs provider for this.
 *
 * <p>{@code targetLanguage} is accepted for interface compatibility with existing callers but not
 * sent to MiniMax -- its voice-clone schema has no language field; language only matters at the
 * synthesis step (VoiceSynthesisService), where it's carried in the dialogue text itself.
 */
@Service
public class LlmGatewayVoiceCloneGenerationService implements VoiceCloneGenerationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayVoiceCloneGenerationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-voice-clone-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public VoiceCloneResult cloneVoice(UUID tenantId, String idempotencyKey, String referenceAudioUrl, String targetLanguage, String modelOverride) {
        if (referenceAudioUrl == null || referenceAudioUrl.isBlank()) {
            throw PostProductionException.badRequest("No reference audio available to clone a voice from");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", referenceAudioUrl);

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? defaultModel : modelOverride;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", "")), params, null, null)
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no cloned voice id");
        }
        BigDecimal cost = response.usage() == null ? BigDecimal.ZERO : response.usage().cost();
        // response() carries the provider's cloned-voice-id as its content, the same convention
        // FalAiProvider uses for a video job's output URI -- one string result field for whatever
        // the model produces, typed downstream by the caller who knows what model it asked for.
        return new VoiceCloneResult(response.jobId(), "fal.ai", response.response(), cost);
    }
}
