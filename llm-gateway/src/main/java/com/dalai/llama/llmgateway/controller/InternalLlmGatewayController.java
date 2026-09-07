package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.dto.LanguageSummary;
import com.dalai.llama.llmgateway.dto.ModelCapabilityView;
import com.dalai.llama.llmgateway.dto.ModelSummary;
import com.dalai.llama.llmgateway.dto.ModelSummaryMapper;
import com.dalai.llama.llmgateway.repository.LanguageMasterRepository;
import com.dalai.llama.llmgateway.repository.LlmJobRepository;
import com.dalai.llama.llmgateway.repository.ModelCapabilityRepository;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import com.dalai.llama.llmgateway.service.ModelRouterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service-to-service surface for every other backend service's LLM/embedding calls -- {@code
 * /api/v1/internal/**} is the permitAll security chain every service already carves out for
 * exactly this purpose (see {@code SecurityConfig}'s internal chain), so calling here needs no
 * JWT: the caller passes {@code tenantId} explicitly in the path, same convention as {@code
 * InternalBillingController}'s {@code /api/v1/internal/tenants/{tenantId}/...} shape. Every
 * in-repo {@code LlmGatewayClient} should call this, not {@code /v1/chat} -- that endpoint stays
 * JWT-protected for any real end-user-authenticated caller, but no service client in this system
 * currently forwards a user's JWT downstream, so it was never actually reachable from another
 * service (see the session note on this gap).
 */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalLlmGatewayController {

    private final LlmGatewayService llmGatewayService;
    private final ModelCapabilityRepository modelCapabilityRepository;
    private final LanguageMasterRepository languageMasterRepository;
    private final LlmJobRepository llmJobRepository;
    private final ModelRouterService modelRouterService;
    private final ModelSummaryMapper modelSummaryMapper;

    public InternalLlmGatewayController(
            LlmGatewayService llmGatewayService,
            ModelCapabilityRepository modelCapabilityRepository,
            LanguageMasterRepository languageMasterRepository,
            LlmJobRepository llmJobRepository,
            ModelRouterService modelRouterService,
            ModelSummaryMapper modelSummaryMapper
    ) {
        this.llmGatewayService = llmGatewayService;
        this.modelCapabilityRepository = modelCapabilityRepository;
        this.languageMasterRepository = languageMasterRepository;
        this.llmJobRepository = llmJobRepository;
        this.modelRouterService = modelRouterService;
        this.modelSummaryMapper = modelSummaryMapper;
    }

    @PostMapping("/tenants/{tenantId}/chat")
    public ChatResponse chat(
            @PathVariable String tenantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ChatRequest request
    ) {
        return llmGatewayService.chat(tenantId, idempotencyKey, request);
    }

    /** Raw per-model-type cost totals for one project -- what the pricing layer (billing-service)
     * reads to build a client quote. Raw only: model_master types + summed cost, no pricing
     * categories or margins (that's billing-service's job, not llm-gateway's). Computed live from
     * llm_job at request time, reflecting real usage as of now. */
    @GetMapping("/tenants/{tenantId}/projects/{projectId}/job-costs")
    public List<JobCostView> jobCosts(@PathVariable String tenantId, @PathVariable UUID projectId) {
        return llmJobRepository.sumCostByTypeForProject(tenantId, projectId).stream()
                .map(row -> new JobCostView(row.getModelType(), row.getCost()))
                .toList();
    }

    public record JobCostView(String modelType, BigDecimal cost) {
    }

    /** Tenant-agnostic in today's implementation (capability rows aren't tenant-scoped), so no
     * {@code tenantId} segment -- matches {@code ModelCapabilityRepository}'s own shape. */
    @GetMapping("/models/{modelId}/capabilities")
    public List<ModelCapabilityView> modelCapabilities(@PathVariable String modelId) {
        return modelCapabilityRepository.findByModelId(modelId).stream()
                .map(c -> new ModelCapabilityView(c.getCapabilityKey(), c.getStrength()))
                .toList();
    }

    /** Internal mirror of {@code LlmGatewayController.listLanguages()} -- that one is behind the
     * JWT-authenticated chain (real end users only), so a service-to-service caller like {@code
     * pre-production-service}'s {@code LlmGatewayClient.listLanguages()} always got 403 hitting it
     * directly with no JWT. Same query, same mapping, reachable from here instead. */
    @GetMapping("/languages")
    public List<LanguageSummary> languages() {
        return languageMasterRepository.findAll().stream()
                .map(l -> new LanguageSummary(l.getLanguageCode(), l.getDisplayName(), l.getNativeName()))
                .toList();
    }

    /** Internal mirror of {@code LlmGatewayController.listModels()} -- same JWT-chain problem as
     * {@link #languages()}: video-generation-service's LlmGatewayClient.listModels() was calling
     * the end-user /v1/models path with no JWT and always got 401 (surfaced live as a 500 on
     * https://api.dalaillama.in/v1/scenes/models?type=video). Same query, same mapping,
     * reachable from here instead. Tenant identity comes in via the X-Tenant-ID header, same
     * shape as the sync {@link #chat} handler. */
    @GetMapping("/models")
    public List<ModelSummary> models(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(required = false) String type
    ) {
        return modelRouterService.listForTenant(tenantId, type).stream()
                .map(modelSummaryMapper::toSummary)
                .toList();
    }
}
