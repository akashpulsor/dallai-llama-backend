package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.web.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmGatewayPromptCompressionService implements PromptCompressionService {

    /** Doc §26: named-entity extraction is an explicitly open decision -- capitalized-token
     * matching is the "simple regex/config-driven now" option named there, not LLM-based NER. */
    private static final Pattern CAPITALIZED_WORD = Pattern.compile("\\b[A-Z][a-zA-Z0-9]{2,}\\b");

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayPromptCompressionService(
            LlmGatewayClient llmGatewayClient,
            @Value("${video-gen.llm-gateway.default-compression-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Override
    public CompressionResult compressIfNeeded(String prompt, int maxLength) {
        if (prompt == null || prompt.length() <= maxLength) {
            return new CompressionResult(prompt, false, prompt == null ? 0 : prompt.length(), prompt == null ? 0 : prompt.length(), true);
        }
        UUID tenantId = TenantContextHolder.get().tenantId();
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "prompt-compression-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(
                        defaultModel,
                        List.of(new LlmGatewayMessage("user", prompt)),
                        Map.of(),
                        "PROMPT_COMPRESSION",
                        Map.of("maxLength", String.valueOf(maxLength))
                )
        );
        String compressed = response == null || response.response() == null ? prompt : response.response();
        boolean validated = validateNamedEntitiesPreserved(prompt, compressed);
        return new CompressionResult(compressed, true, prompt.length(), compressed.length(), validated);
    }

    private boolean validateNamedEntitiesPreserved(String original, String compressed) {
        Matcher matcher = CAPITALIZED_WORD.matcher(original);
        while (matcher.find()) {
            if (!compressed.contains(matcher.group())) {
                return false;
            }
        }
        return true;
    }
}
