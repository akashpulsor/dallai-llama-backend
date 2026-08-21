package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.postprod.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Translation is plain text -- reuses the existing /v1/chat text path (gemini-2.5-flash via
 * GoogleGeminiProvider, already live and working), not a fal.ai model. Same
 * task-key/PromptTemplate registry every other task-specific prompt in llm-gateway uses
 * (PHONEME_GUIDE, PROMPT_COMPRESSION, ...) -- see V20's TRANSLATE_DIALOGUE seed. */
@Service
public class LlmGatewayTranslationService implements TranslationService {

    private final LlmGatewayClient llmGatewayClient;
    private final String textModel;

    public LlmGatewayTranslationService(
            LlmGatewayClient llmGatewayClient,
            @Value("${post-production.llm-gateway.default-text-model}") String textModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.textModel = textModel;
    }

    @Override
    public String translate(String tenantId, String idempotencyKey, String transcript, String sourceLanguage, String targetLanguage) {
        if (transcript == null || transcript.isBlank()) {
            throw PostProductionException.badRequest("No transcript to translate");
        }
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId,
                idempotencyKey,
                new LlmGatewayChatRequest(
                        textModel,
                        List.of(new LlmGatewayMessage("user", transcript)),
                        null,
                        "TRANSLATE_DIALOGUE",
                        Map.of("sourceLanguage", sourceLanguage, "targetLanguage", targetLanguage)
                )
        );
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PostProductionException.upstream("llm-gateway returned no translated transcript");
        }
        return response.response();
    }
}
