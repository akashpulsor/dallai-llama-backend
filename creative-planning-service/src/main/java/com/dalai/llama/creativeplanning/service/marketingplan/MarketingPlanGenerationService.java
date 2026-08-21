package com.dalai.llama.creativeplanning.service.marketingplan;

import com.dalai.llama.creativeplanning.domain.MarketingPlanCritiqueVerdict;
import com.dalai.llama.creativeplanning.domain.MarketingPlanStatus;
import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.GenerateMarketingPlanRequest;
import com.dalai.llama.creativeplanning.dto.MarketingPlanGenerationResultView;
import com.dalai.llama.creativeplanning.dto.MarketingPlanView;
import com.dalai.llama.creativeplanning.repository.MarketingPlanRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.generation.ContextSummaryBuilder;
import com.dalai.llama.creativeplanning.service.generation.JsonExtraction;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.creativeplanning.service.marketingplan.critic.MarketingPlanCritiqueApiRequest;
import com.dalai.llama.creativeplanning.service.marketingplan.critic.MarketingPlanCritiqueApiResult;
import com.dalai.llama.creativeplanning.service.marketingplan.critic.MarketingPlanCriticServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a full strategic marketing/branding plan grounded in brand + product + target-
 * audience context, framed as an Ivy League-caliber strategist drawing on real case studies (see
 * the MARKETING_PLAN_GENERATION prompt in llm-gateway). Critic ownership note: the harness that
 * pre-flight-reviews the generated plan lives in critic-service -- the dedicated service for
 * critic work in this system -- not here. This service only calls it via {@link
 * MarketingPlanCriticServiceClient}, exactly the way pre-production-service calls critic-service
 * for shot critiques; it never re-implements critique logic locally.
 */
@Service
public class MarketingPlanGenerationService {

    private static final String GENERATION_TASK_KEY = "MARKETING_PLAN_GENERATION";

    private final MarketingPlanRepository marketingPlanRepository;
    private final BrandContextService brandContextService;
    private final ProductProfileService productProfileService;
    private final LlmGatewayClient llmGatewayClient;
    private final MarketingPlanCriticServiceClient criticServiceClient;
    private final ChatServiceClient chatServiceClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public MarketingPlanGenerationService(
            MarketingPlanRepository marketingPlanRepository,
            BrandContextService brandContextService,
            ProductProfileService productProfileService,
            LlmGatewayClient llmGatewayClient,
            MarketingPlanCriticServiceClient criticServiceClient,
            ChatServiceClient chatServiceClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.marketingPlanRepository = marketingPlanRepository;
        this.brandContextService = brandContextService;
        this.productProfileService = productProfileService;
        this.llmGatewayClient = llmGatewayClient;
        this.criticServiceClient = criticServiceClient;
        this.chatServiceClient = chatServiceClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public MarketingPlanGenerationResultView generate(UUID tenantId, GenerateMarketingPlanRequest request) {
        BrandContext brand = brandContextService.requireBrand(tenantId);
        ProductProfile product = request.productProfileId() == null ? null
                : productProfileService.requireProduct(tenantId, request.productProfileId());

        MarketingPlanContent content = callGeneration(tenantId, brand, product, request);

        MarketingPlan plan = MarketingPlan.builder()
                .tenantId(tenantId)
                .brandContextId(brand.getId())
                .productProfileId(product == null ? null : product.getId())
                .targetAudienceInput(request.targetAudienceInput())
                .budgetTier(request.budgetTier())
                .status(MarketingPlanStatus.DRAFT)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        MarketingPlanContentMapper.applyTo(plan, content);
        plan = marketingPlanRepository.save(plan);

        return applyHarnessAndFinalize(tenantId, plan, content, brandAndAudienceContext(brand, product, request.targetAudienceInput()));
    }

    private MarketingPlanGenerationResultView applyHarnessAndFinalize(UUID tenantId, MarketingPlan plan,
                                                                        MarketingPlanContent content, String brandAndAudienceContext) {
        MarketingPlanCritiqueApiResult critique = criticServiceClient.critique(tenantId.toString(),
                new MarketingPlanCritiqueApiRequest(plan.getId(), content, brandAndAudienceContext));

        MarketingPlanContent finalContent = critique.revisedContent() != null ? critique.revisedContent() : content;
        MarketingPlanContentMapper.applyTo(plan, finalContent);
        plan.setStatus(critique.verdict() == MarketingPlanCritiqueVerdict.PASS
                ? MarketingPlanStatus.FINAL : MarketingPlanStatus.NEEDS_REVIEW);
        plan.setUpdatedAt(OffsetDateTime.now());
        marketingPlanRepository.save(plan);
        chatServiceClient.ingestMarketingPlan(tenantId, plan.getId(), MarketingPlanContentMapper.toEmbeddingText(plan));

        return new MarketingPlanGenerationResultView(MarketingPlanContentMapper.toView(plan), critique.sessionId(), critique.findings());
    }

    @Transactional(readOnly = true)
    public MarketingPlanView get(UUID tenantId, UUID planId) {
        return MarketingPlanContentMapper.toView(require(tenantId, planId));
    }

    @Transactional(readOnly = true)
    public List<MarketingPlanView> list(UUID tenantId) {
        return marketingPlanRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(MarketingPlanContentMapper::toView)
                .toList();
    }

    public MarketingPlan require(UUID tenantId, UUID planId) {
        return marketingPlanRepository.findByIdAndTenantId(planId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No marketing plan " + planId));
    }

    /** Re-runs the harness against the current plan content and finalizes -- shared by both
     * initial generation and {@code MarketingPlanRevisionService}'s explicit user-driven revision,
     * so the mandatory critic gate is never bypassed regardless of which path produced new content. */
    @Transactional
    public MarketingPlanGenerationResultView reCritiqueAndFinalize(UUID tenantId, MarketingPlan plan, MarketingPlanContent content) {
        BrandContext brand = brandContextService.requireBrand(tenantId);
        ProductProfile product = plan.getProductProfileId() == null ? null
                : productProfileService.requireProduct(tenantId, plan.getProductProfileId());
        return applyHarnessAndFinalize(tenantId, plan, content, brandAndAudienceContext(brand, product, plan.getTargetAudienceInput()));
    }

    private MarketingPlanContent callGeneration(UUID tenantId, BrandContext brand, ProductProfile product, GenerateMarketingPlanRequest request) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "marketing-plan-generate-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, GENERATION_TASK_KEY,
                        Map.of(
                                "brandContext", ContextSummaryBuilder.brandSummary(brand),
                                "productContext", ContextSummaryBuilder.productSummary(product),
                                "targetAudienceInput", request.targetAudienceInput(),
                                "budgetTier", request.budgetTier().name()
                        )));
        return parse(response, GENERATION_TASK_KEY);
    }

    private MarketingPlanContent parse(LlmGatewayChatResponse response, String taskKey) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + taskKey);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MarketingPlanContent.class);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not parse " + taskKey + " response as JSON: " + ex.getMessage());
        }
    }

    private String brandAndAudienceContext(BrandContext brand, ProductProfile product, String targetAudienceInput) {
        return ContextSummaryBuilder.brandSummary(brand) + " " + ContextSummaryBuilder.productSummary(product)
                + " Target audience for this plan: " + (targetAudienceInput == null || targetAudienceInput.isBlank() ? "(not specified)" : targetAudienceInput);
    }
}
