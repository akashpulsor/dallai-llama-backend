package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.CampaignSessionStatus;
import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningMessage;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningMessageRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProductProfileRepository;
import com.dalai.llama.creativeplanning.service.generation.ContextSummaryBuilder;
import com.dalai.llama.creativeplanning.service.generation.JsonExtraction;
import com.dalai.llama.creativeplanning.service.generation.LockedIdeaExtractionResult;
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

/** Turns a campaign planning conversation the team is happy with into one structured {@link
 * LockedIdea} -- the handoff artifact pre-production-service's {@code POST
 * /v1/projects/from-locked-idea} consumes by id. Locking is one-way: the session moves to LOCKED
 * and stops accepting new messages (see {@code CampaignPlanningChatService}). */
@Service
public class LockedIdeaService {

    private static final String TASK_KEY = "LOCKED_IDEA_EXTRACTION";

    private final CampaignSessionService campaignSessionService;
    private final CampaignPlanningMessageRepository campaignPlanningMessageRepository;
    private final BrandContextService brandContextService;
    private final ProductProfileRepository productProfileRepository;
    private final LockedIdeaRepository lockedIdeaRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LockedIdeaService(
            CampaignSessionService campaignSessionService,
            CampaignPlanningMessageRepository campaignPlanningMessageRepository,
            BrandContextService brandContextService,
            ProductProfileRepository productProfileRepository,
            LockedIdeaRepository lockedIdeaRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.campaignSessionService = campaignSessionService;
        this.campaignPlanningMessageRepository = campaignPlanningMessageRepository;
        this.brandContextService = brandContextService;
        this.productProfileRepository = productProfileRepository;
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public LockedIdeaView lock(UUID tenantId, UUID sessionId) {
        CampaignPlanningSession session = campaignSessionService.requireSession(tenantId, sessionId);
        if (session.getStatus() == CampaignSessionStatus.LOCKED) {
            return toView(lockedIdeaRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> CreativePlanningException.conflict("Session " + sessionId + " is locked but has no locked idea -- inconsistent state")));
        }

        List<CampaignPlanningMessage> history = campaignPlanningMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (history.isEmpty()) {
            throw CreativePlanningException.badRequest("Session " + sessionId + " has no conversation to extract an idea from");
        }
        BrandContext brand = brandContextService.requireBrand(tenantId);
        ProductProfile product = session.getProductProfileId() == null ? null
                : productProfileRepository.findByIdAndTenantId(session.getProductProfileId(), tenantId).orElse(null);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "locked-idea-" + sessionId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "brandContext", ContextSummaryBuilder.brandSummary(brand),
                                "productContext", ContextSummaryBuilder.productSummary(product),
                                "conversationHistory", ContextSummaryBuilder.conversationHistory(history)
                        )));

        LockedIdeaExtractionResult parsed = parse(response);

        LockedIdea lockedIdea = lockedIdeaRepository.save(LockedIdea.builder()
                .tenantId(tenantId)
                .sessionId(sessionId)
                .title(parsed.title())
                .concept(parsed.concept())
                .targetAudience(parsed.targetAudience())
                .campaignAngle(parsed.campaignAngle())
                .keyMessage(parsed.keyMessage())
                .tone(parsed.tone())
                .budgetTier(session.getBudgetTier())
                .createdAt(OffsetDateTime.now())
                .build());

        campaignSessionService.markLocked(session);
        return toView(lockedIdea);
    }

    @Transactional(readOnly = true)
    public LockedIdeaView get(UUID tenantId, UUID lockedIdeaId) {
        return toView(lockedIdeaRepository.findByIdAndTenantId(lockedIdeaId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No locked idea " + lockedIdeaId)));
    }

    private LockedIdeaExtractionResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), LockedIdeaExtractionResult.class);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private LockedIdeaView toView(LockedIdea idea) {
        return new LockedIdeaView(idea.getId(), idea.getSessionId(), idea.getTitle(), idea.getConcept(),
                idea.getTargetAudience(), idea.getCampaignAngle(), idea.getKeyMessage(), idea.getTone(),
                idea.getBudgetTier(), idea.getCreatedAt());
    }
}
