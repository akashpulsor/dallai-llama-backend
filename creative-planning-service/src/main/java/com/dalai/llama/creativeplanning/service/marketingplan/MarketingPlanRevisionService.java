package com.dalai.llama.creativeplanning.service.marketingplan;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlanMessage;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.MarketingPlanGenerationResultView;
import com.dalai.llama.creativeplanning.dto.ReviseMarketingPlanRequest;
import com.dalai.llama.creativeplanning.repository.MarketingPlanMessageRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.generation.ContextSummaryBuilder;
import com.dalai.llama.creativeplanning.service.generation.JsonExtraction;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit action, not an inline chat edit: {@code POST /v1/marketing-plans/{id}/revise} takes
 * the user's stated instructions, regenerates the plan content around them, then routes the
 * result back through {@link MarketingPlanGenerationService#reCritiqueAndFinalize} -- the harness
 * in critic-service reviews every version of a plan that could become FINAL, not just the first
 * one. Same "chat suggests, an explicit call commits" discipline as {@code LockedIdeaService}.
 */
@Service
public class MarketingPlanRevisionService {

    private static final String TASK_KEY = "MARKETING_PLAN_USER_REVISION";

    private final MarketingPlanGenerationService marketingPlanGenerationService;
    private final MarketingPlanMessageRepository marketingPlanMessageRepository;
    private final BrandContextService brandContextService;
    private final ProductProfileService productProfileService;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public MarketingPlanRevisionService(
            MarketingPlanGenerationService marketingPlanGenerationService,
            MarketingPlanMessageRepository marketingPlanMessageRepository,
            BrandContextService brandContextService,
            ProductProfileService productProfileService,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.marketingPlanGenerationService = marketingPlanGenerationService;
        this.marketingPlanMessageRepository = marketingPlanMessageRepository;
        this.brandContextService = brandContextService;
        this.productProfileService = productProfileService;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public MarketingPlanGenerationResultView revise(UUID tenantId, UUID planId, ReviseMarketingPlanRequest request) {
        MarketingPlan plan = marketingPlanGenerationService.require(tenantId, planId);
        BrandContext brand = brandContextService.requireBrand(tenantId, plan.getBrandContextId());
        ProductProfile product = plan.getProductProfileId() == null ? null
                : productProfileService.requireProduct(tenantId, plan.getProductProfileId());
        List<MarketingPlanMessage> history = marketingPlanMessageRepository.findByPlanIdOrderByCreatedAtAsc(planId);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "marketing-plan-revise-" + planId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "brandContext", ContextSummaryBuilder.brandSummary(brand),
                                "productContext", ContextSummaryBuilder.productSummary(product),
                                "marketingPlanJson", writeJson(plan),
                                "instructions", request.instructions(),
                                "conversationHistory", conversationHistory(history)
                        )));

        MarketingPlanContent revisedContent = parse(response);
        return marketingPlanGenerationService.reCritiqueAndFinalize(tenantId, plan, revisedContent);
    }

    private String writeJson(MarketingPlan plan) {
        try {
            return objectMapper.writeValueAsString(MarketingPlanContentMapper.fromEntity(plan));
        } catch (Exception ex) {
            throw CreativePlanningException.badRequest("Could not serialize marketing plan for revision: " + ex.getMessage());
        }
    }

    private MarketingPlanContent parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MarketingPlanContent.class);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private String conversationHistory(List<MarketingPlanMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no messages yet)";
        }
        return messages.stream().map(m -> m.getRole() + ": " + m.getContent())
                .reduce((a, b) -> a + "\n" + b).orElse("(no messages yet)");
    }
}
