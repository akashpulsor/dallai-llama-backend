package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.dto.EstimateResponse;
import com.dalai.llama.llmgateway.dto.JobStatusResponse;
import com.dalai.llama.llmgateway.dto.LanguageSummary;
import com.dalai.llama.llmgateway.dto.ModelCapabilityView;
import com.dalai.llama.llmgateway.dto.ModelSummary;
import com.dalai.llama.llmgateway.dto.ModelSummaryMapper;
import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import com.dalai.llama.llmgateway.repository.LanguageMasterRepository;
import com.dalai.llama.llmgateway.repository.ModelCapabilityRepository;
import com.dalai.llama.llmgateway.repository.ModelSupportedLanguageRepository;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import com.dalai.llama.llmgateway.service.ModelRouterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Doc §16 v1 surface: {@code POST /v1/chat} (sync only -- {@code /v1/chat/async} is not built),
 * {@code POST /v1/estimate}, {@code GET /v1/jobs/{id}}, {@code GET /v1/models}. Tenant identity
 * is the {@code X-Tenant-ID} header, matching the convention already used by every
 * creator-service controller rather than a new JWT-claim convention.
 */
@RestController
public class LlmGatewayController {

    private final LlmGatewayService llmGatewayService;
    private final ModelRouterService modelRouterService;
    private final ModelSummaryMapper modelSummaryMapper;
    private final LanguageMasterRepository languageMasterRepository;
    private final ModelSupportedLanguageRepository modelSupportedLanguageRepository;
    private final ModelCapabilityRepository modelCapabilityRepository;

    public LlmGatewayController(
            LlmGatewayService llmGatewayService,
            ModelRouterService modelRouterService,
            ModelSummaryMapper modelSummaryMapper,
            LanguageMasterRepository languageMasterRepository,
            ModelSupportedLanguageRepository modelSupportedLanguageRepository,
            ModelCapabilityRepository modelCapabilityRepository
    ) {
        this.llmGatewayService = llmGatewayService;
        this.modelRouterService = modelRouterService;
        this.modelSummaryMapper = modelSummaryMapper;
        this.languageMasterRepository = languageMasterRepository;
        this.modelSupportedLanguageRepository = modelSupportedLanguageRepository;
        this.modelCapabilityRepository = modelCapabilityRepository;
    }

    @PostMapping("/v1/chat")
    public ResponseEntity<ChatResponse> chat(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ChatRequest request
    ) {
        return ResponseEntity.ok(llmGatewayService.chat(tenantId, idempotencyKey, request));
    }

    @PostMapping("/v1/estimate")
    public ResponseEntity<EstimateResponse> estimate(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @Valid @RequestBody ChatRequest request
    ) {
        return ResponseEntity.ok(llmGatewayService.estimate(tenantId, request));
    }

    @GetMapping("/v1/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> jobStatus(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(llmGatewayService.jobStatus(tenantId, jobId));
    }

    @PostMapping("/v1/jobs/{jobId}/cancel")
    public ResponseEntity<JobStatusResponse> cancel(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(llmGatewayService.cancel(tenantId, jobId));
    }

    @GetMapping("/v1/models")
    public ResponseEntity<List<ModelSummary>> listModels(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(required = false) String type
    ) {
        List<ModelSummary> models = modelRouterService.listForTenant(tenantId, type).stream()
                .map(modelSummaryMapper::toSummary)
                .toList();
        return ResponseEntity.ok(models);
    }

    /** The canonical language list -- every language a caller could reference by code, regardless
     * of which model/provider ends up handling it. */
    @GetMapping("/v1/languages")
    public ResponseEntity<List<LanguageSummary>> listLanguages() {
        List<LanguageSummary> languages = languageMasterRepository.findAll().stream()
                .map(l -> new LanguageSummary(l.getLanguageCode(), l.getDisplayName(), l.getNativeName()))
                .toList();
        return ResponseEntity.ok(languages);
    }

    /** Which languages a specific model actually supports -- e.g. GET
     * /v1/models/voice-clone-v1/languages, so a caller building a language picker for a
     * particular voice-clone/TTS model doesn't offer languages that model can't handle. */
    @GetMapping("/v1/models/{modelId}/languages")
    public ResponseEntity<List<LanguageSummary>> listModelLanguages(@PathVariable String modelId) {
        List<String> codes = modelSupportedLanguageRepository.findByIdModelId(modelId).stream()
                .map(row -> row.getId().getLanguageCode())
                .toList();
        List<LanguageSummary> languages = languageMasterRepository.findAllById(codes).stream()
                .map(l -> new LanguageSummary(l.getLanguageCode(), l.getDisplayName(), l.getNativeName()))
                .toList();
        return ResponseEntity.ok(languages);
    }

    /** Feeds critic-service's Level 4 generation-feasibility check -- how strong the given model
     * is at named capabilities (CAMERA_MOTION, OBJECT_CONSISTENCY, ...), see {@code
     * model_capability}. */
    @GetMapping("/v1/models/{modelId}/capabilities")
    public ResponseEntity<List<ModelCapabilityView>> listModelCapabilities(@PathVariable String modelId) {
        List<ModelCapabilityView> capabilities = modelCapabilityRepository.findByModelId(modelId).stream()
                .map(c -> new ModelCapabilityView(c.getCapabilityKey(), c.getStrength()))
                .toList();
        return ResponseEntity.ok(capabilities);
    }
}
