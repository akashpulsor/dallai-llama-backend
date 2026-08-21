package com.dalai.llama.creativeplanning.service.marketingplan;

import com.dalai.llama.creativeplanning.domain.MessageRole;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlanMessage;
import com.dalai.llama.creativeplanning.dto.MarketingPlanMessageView;
import com.dalai.llama.creativeplanning.dto.SendMessageRequest;
import com.dalai.llama.creativeplanning.repository.MarketingPlanMessageRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "Talk to your marketing plan" -- Q&A/discussion grounded in the full plan content, mirrors
 * {@code CampaignPlanningChatService}. Requesting an actual change doesn't edit the plan inline:
 * the strategist can discuss and recommend here, but committing a change is
 * {@code MarketingPlanRevisionService}'s explicit action -- same chat-suggests/explicit-action-
 * commits discipline used across this service. */
@Service
public class MarketingPlanChatService {

    private static final String TASK_KEY = "MARKETING_PLAN_CHAT";

    private final MarketingPlanGenerationService marketingPlanGenerationService;
    private final MarketingPlanMessageRepository marketingPlanMessageRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public MarketingPlanChatService(
            MarketingPlanGenerationService marketingPlanGenerationService,
            MarketingPlanMessageRepository marketingPlanMessageRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.marketingPlanGenerationService = marketingPlanGenerationService;
        this.marketingPlanMessageRepository = marketingPlanMessageRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public MarketingPlanMessageView sendMessage(UUID tenantId, UUID planId, SendMessageRequest request) {
        MarketingPlan plan = marketingPlanGenerationService.require(tenantId, planId);

        OffsetDateTime now = OffsetDateTime.now();
        marketingPlanMessageRepository.save(MarketingPlanMessage.builder()
                .planId(planId)
                .tenantId(tenantId)
                .role(MessageRole.USER)
                .content(request.content())
                .createdAt(now)
                .build());

        List<MarketingPlanMessage> history = marketingPlanMessageRepository.findByPlanIdOrderByCreatedAtAsc(planId);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "marketing-plan-chat-" + planId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")), null, TASK_KEY,
                        Map.of(
                                "marketingPlanJson", writeJson(plan),
                                "conversationHistory", conversationHistory(history)
                        )));

        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }

        MarketingPlanMessage assistantMessage = marketingPlanMessageRepository.save(MarketingPlanMessage.builder()
                .planId(planId)
                .tenantId(tenantId)
                .role(MessageRole.ASSISTANT)
                .content(response.response())
                .createdAt(OffsetDateTime.now())
                .build());

        return toView(assistantMessage);
    }

    @Transactional(readOnly = true)
    public List<MarketingPlanMessageView> history(UUID tenantId, UUID planId) {
        marketingPlanGenerationService.require(tenantId, planId);
        return marketingPlanMessageRepository.findByPlanIdOrderByCreatedAtAsc(planId).stream()
                .map(this::toView)
                .toList();
    }

    private String writeJson(MarketingPlan plan) {
        try {
            return objectMapper.writeValueAsString(MarketingPlanContentMapper.fromEntity(plan));
        } catch (Exception ex) {
            throw CreativePlanningException.badRequest("Could not serialize marketing plan for chat: " + ex.getMessage());
        }
    }

    private String conversationHistory(List<MarketingPlanMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no messages yet)";
        }
        return messages.stream().map(m -> m.getRole() + ": " + m.getContent())
                .reduce((a, b) -> a + "\n" + b).orElse("(no messages yet)");
    }

    private MarketingPlanMessageView toView(MarketingPlanMessage message) {
        return new MarketingPlanMessageView(message.getRole(), message.getContent(), message.getCreatedAt());
    }
}
