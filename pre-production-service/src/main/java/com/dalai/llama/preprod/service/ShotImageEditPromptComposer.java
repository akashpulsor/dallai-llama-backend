package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rewrites a creator's raw "fix the on-image text" chat note into an edit-safe prompt the image
 * model will follow without blowing away the rest of the frame. Called from
 * {@link ChangeRequestService#create} only when a suggested change targets SHOT_IMAGE -- for
 * every other target type the raw note flows through unchanged, same as before.
 *
 * <p>Never throws on failure: composition is a quality improvement, not a hard requirement. When
 * the LLM call/parse fails we return the raw creator note verbatim, so the flow degrades to the
 * pre-existing (worse but working) behavior instead of blocking a chat suggestion. */
@Service
public class ShotImageEditPromptComposer {

    private static final String TASK_KEY = "PRE_PROD_SHOT_IMAGE_EDIT_COMPOSE";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ShotImageEditPromptComposer(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    public String compose(UUID tenantId, String creatorNote, String currentOnScreenText) {
        if (creatorNote == null || creatorNote.isBlank()) {
            return creatorNote;
        }
        try {
            String userMessage = "currentOnScreenText: " + (currentOnScreenText == null ? "null" : "\"" + currentOnScreenText + "\"")
                    + "\ncreatorNote: \"" + creatorNote + "\"";
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "shot-image-edit-compose-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(defaultModel,
                            List.of(new LlmGatewayMessage("user", userMessage)),
                            JsonExtraction.JSON_MODE_PARAMS, TASK_KEY, Map.of()));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return creatorNote;
            }
            ComposeResult parsed = objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ComposeResult.class);
            if (parsed.editPrompt() == null || parsed.editPrompt().isBlank()) {
                return creatorNote;
            }
            return parsed.editPrompt();
        } catch (Exception ex) {
            return creatorNote;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ComposeResult(String editPrompt) {
    }
}
