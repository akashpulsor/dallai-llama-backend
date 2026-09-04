package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.CampaignSessionStatus;
import com.dalai.llama.creativeplanning.domain.MessageRole;
import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningMessage;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.CampaignPlanningMessageView;
import com.dalai.llama.creativeplanning.dto.SendMessageRequest;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningMessageRepository;
import com.dalai.llama.creativeplanning.repository.ProductProfileRepository;
import com.dalai.llama.creativeplanning.service.generation.ContextSummaryBuilder;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The "marketing and branding team" conversation itself -- each call persists the user's
 * message, asks llm-gateway for the strategist's next reply grounded in brand/product context,
 * and persists that too. Plain-text response, not JSON: this is a conversation, not a structured
 * extraction (that's {@link LockedIdeaService}'s job, once the team is happy with where it landed). */
@Service
public class CampaignPlanningChatService {

    private static final String TASK_KEY = "CAMPAIGN_PLANNING_CHAT";

    private final CampaignSessionService campaignSessionService;
    private final CampaignPlanningMessageRepository campaignPlanningMessageRepository;
    private final BrandContextService brandContextService;
    private final ProductProfileRepository productProfileRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public CampaignPlanningChatService(
            CampaignSessionService campaignSessionService,
            CampaignPlanningMessageRepository campaignPlanningMessageRepository,
            BrandContextService brandContextService,
            ProductProfileRepository productProfileRepository,
            LlmGatewayClient llmGatewayClient,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.campaignSessionService = campaignSessionService;
        this.campaignPlanningMessageRepository = campaignPlanningMessageRepository;
        this.brandContextService = brandContextService;
        this.productProfileRepository = productProfileRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public CampaignPlanningMessageView sendMessage(UUID tenantId, UUID sessionId, SendMessageRequest request) {
        CampaignPlanningSession session = campaignSessionService.requireSession(tenantId, sessionId);
        if (session.getStatus() != CampaignSessionStatus.ACTIVE) {
            throw CreativePlanningException.conflict("Session " + sessionId + " is already locked");
        }

        OffsetDateTime now = OffsetDateTime.now();
        campaignPlanningMessageRepository.save(CampaignPlanningMessage.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .role(MessageRole.USER)
                .content(request.content())
                .createdAt(now)
                .build());

        BrandContext brand = brandContextService.requireBrand(tenantId, session.getBrandContextId());
        ProductProfile product = session.getProductProfileId() == null ? null
                : productProfileRepository.findByIdAndTenantId(session.getProductProfileId(), tenantId).orElse(null);
        List<CampaignPlanningMessage> history = campaignPlanningMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "campaign-chat-" + sessionId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")), null, TASK_KEY,
                        Map.of(
                                "brandContext", ContextSummaryBuilder.brandSummary(brand),
                                "productContext", ContextSummaryBuilder.productSummary(product),
                                "referenceImageContext", "(reference image analyses are attached to the product, not summarized here in this v1 slice)",
                                "conversationHistory", ContextSummaryBuilder.conversationHistory(history)
                        )));

        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }

        CampaignPlanningMessage assistantMessage = campaignPlanningMessageRepository.save(CampaignPlanningMessage.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .role(MessageRole.ASSISTANT)
                .content(response.response())
                .createdAt(OffsetDateTime.now())
                .build());

        return toView(assistantMessage);
    }

    @Transactional(readOnly = true)
    public List<CampaignPlanningMessageView> history(UUID tenantId, UUID sessionId) {
        campaignSessionService.requireSession(tenantId, sessionId);
        return campaignPlanningMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private CampaignPlanningMessageView toView(CampaignPlanningMessage message) {
        return new CampaignPlanningMessageView(message.getRole(), message.getContent(), message.getCreatedAt());
    }
}
