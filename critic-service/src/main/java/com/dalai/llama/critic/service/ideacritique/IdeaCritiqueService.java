package com.dalai.llama.critic.service.ideacritique;

import com.dalai.llama.critic.dto.idea.IdeaCandidateItem;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueItem;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueRequest;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueResult;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueVerdict;
import com.dalai.llama.critic.service.CriticException;
import com.dalai.llama.critic.service.CritiqueThoughtService;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Scores a batch of not-yet-persisted idea candidates from creative-planning-service's
 * {@code ProjectRequirementIdeaService} against three dimensions -- how completely the idea uses
 * everything the client actually gave (brief, brand/product, reference-image analysis), whether it
 * has a real story shape rather than a generic product description, and whether its angle is
 * specific/ownable rather than something any competitor could claim. The last two intentionally
 * substitute for "trending moment marketing" and "competitor research" -- this system has no live
 * internet access, so instead of asking the model to name real trends or competitors (which it
 * can't verify), the rubric asks it to be *specific* rather than *generic*, the same technique
 * {@code StrategyCritic} already uses for marketing-plan case-study references.
 * <p>
 * No auto-revision step (unlike {@code MarketingPlanCritiqueOrchestrator}): regenerating a whole
 * idea from scratch is cheap, so a bad batch is handled by creative-planning-service retrying
 * generation with the concerns fed back as feedback, not by this service patching one field at a
 * time. Findings aren't persisted to their own table either -- creative-planning-service persists
 * whatever it needs directly onto its own {@code idea_option} rows; only the step-by-step
 * reasoning goes through the shared {@link CritiqueThoughtService}, keyed by the returned
 * {@code sessionId}, for a creator who wants the full trace.
 */
@Service
public class IdeaCritiqueService {

    private static final String TASK_KEY = "IDEA_CRITIQUE";

    private final LlmGatewayClient llmGatewayClient;
    private final CritiqueThoughtService critiqueThoughtService;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public IdeaCritiqueService(
            LlmGatewayClient llmGatewayClient,
            CritiqueThoughtService critiqueThoughtService,
            ObjectMapper objectMapper,
            @Value("${critic.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.critiqueThoughtService = critiqueThoughtService;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public IdeaCritiqueResult critique(UUID tenantId, IdeaCritiqueRequest request) {
        UUID sessionId = UUID.randomUUID();
        critiqueThoughtService.log(tenantId, sessionId, "IDEA_CRITIQUE_STARTED",
                "Scoring " + request.candidates().size() + " idea candidate(s)");

        String candidatesJson = writeJson(request.candidates());
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                TASK_KEY + "-" + sessionId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "briefText", request.briefText(),
                                "targetAudience", orNotSpecified(request.targetAudience()),
                                "campaignDirection", orNotSpecified(request.campaignDirection()),
                                "referenceImageAnalysis", orNotSpecified(request.referenceImageAnalysis()),
                                "candidatesJson", candidatesJson
                        )));

        IdeaCritiqueResponse parsed = parseResponse(response);
        List<IdeaCritiqueItem> items = parsed.items() == null ? List.of() : parsed.items();

        long passCount = items.stream().filter(item -> item.verdict() == IdeaCritiqueVerdict.PASS).count();
        critiqueThoughtService.log(tenantId, sessionId, "IDEA_CRITIQUE_SCORED",
                items.size() + " candidate(s) scored, " + passCount + " passed");

        return new IdeaCritiqueResult(sessionId, items);
    }

    private String orNotSpecified(String value) {
        return value == null || value.isBlank() ? "(not specified)" : value;
    }

    private String writeJson(List<IdeaCandidateItem> candidates) {
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (Exception ex) {
            throw CriticException.badRequest("Could not serialize idea candidates for critique: " + ex.getMessage());
        }
    }

    private IdeaCritiqueResponse parseResponse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CriticException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), IdeaCritiqueResponse.class);
        } catch (Exception ex) {
            throw CriticException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdeaCritiqueResponse(List<IdeaCritiqueItem> items) {
    }
}
