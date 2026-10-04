package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.dto.generationplan.PromptReviewFindingView;
import com.dalai.llama.videogen.service.JsonExtraction;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The video studio's three model calls, each rendered from its llm-gateway template and read into
 * a typed answer.
 *
 * <p>Unlike the best-effort prompt writer, these fail out loud: the creator pressed a button to get
 * this answer, so "the model could not be read" is something they see and can retry, never a
 * silent substitute. Whether an answer that parsed is also RIGHT is {@link GenerationPlanValidator}'s
 * job, not this class's.
 */
@Slf4j
@Component
public class GenerationPlanLlm {

    static final String ASSESS = "PRE_PROD_VIDEO_DURATION_ASSESS";
    static final String TIMELINE = "PRE_PROD_VIDEO_SOURCE_TIMELINE";
    static final String REVIEW = "PRE_PROD_VIDEO_PROMPT_REVIEW";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public GenerationPlanLlm(LlmGatewayClient llmGatewayClient, ObjectMapper objectMapper,
                             @Value("${video-gen.generation-plan.model:gemini-2.5-flash}") String model) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssessmentAnswer(Boolean shorterGenerationSuitable, BigDecimal minimumViableDurationSeconds,
                                   Integer recommendedDurationSeconds, Integer recommendedGenerationFps,
                                   String reasoning, List<CoverageAnswer> actionCoverage, List<String> risks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageAnswer(String actionId, BigDecimal startSeconds, BigDecimal endSeconds, Boolean preserved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelineAnswer(List<IntervalAnswer> intervals) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntervalAnswer(BigDecimal startSeconds, BigDecimal endSeconds, String actionId, String action,
                                 String subjectState, String cameraBehavior, Boolean holdRequired) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewAnswer(List<PromptReviewFindingView> findings) {
    }

    public AssessmentAnswer assess(UUID tenantId, UUID projectId, Map<String, String> variables) {
        return ask(tenantId, projectId, ASSESS, variables, AssessmentAnswer.class, "duration assessment");
    }

    public List<IntervalAnswer> timeline(UUID tenantId, UUID projectId, Map<String, String> variables) {
        TimelineAnswer answer = ask(tenantId, projectId, TIMELINE, variables, TimelineAnswer.class, "action timeline");
        if (answer.intervals() == null || answer.intervals().isEmpty()) {
            throw VideoGenException.upstream("The action timeline came back empty -- try again.");
        }
        return answer.intervals();
    }

    public List<PromptReviewFindingView> review(UUID tenantId, UUID projectId, Map<String, String> variables) {
        ReviewAnswer answer = ask(tenantId, projectId, REVIEW, variables, ReviewAnswer.class, "prompt review");
        return answer.findings() == null ? List.of() : answer.findings();
    }

    private <T> T ask(UUID tenantId, UUID projectId, String taskKey, Map<String, String> variables,
                      Class<T> type, String what) {
        LlmGatewayChatResponse response;
        try {
            response = llmGatewayClient.chat(
                    tenantId.toString(),
                    taskKey.toLowerCase() + "-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(model, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, taskKey, variables, projectId));
        } catch (RuntimeException ex) {
            log.warn("{} call failed taskKey={}: {}", what, taskKey, ex.getMessage());
            throw VideoGenException.upstream("Could not get the " + what + " from the model -- try again. (" + ex.getMessage() + ")");
        }
        String raw = response == null ? null : JsonExtraction.stripCodeFence(response.response());
        if (raw == null || raw.isBlank()) {
            throw VideoGenException.upstream("The " + what + " came back empty -- try again.");
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception ex) {
            log.warn("{} was not readable taskKey={}: {}", what, taskKey, ex.getMessage());
            throw VideoGenException.upstream("The " + what + " came back in a shape that could not be read -- try again.");
        }
    }
}
