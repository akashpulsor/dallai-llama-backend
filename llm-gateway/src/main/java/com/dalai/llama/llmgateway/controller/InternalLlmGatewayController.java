package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.dto.ModelCapabilityView;
import com.dalai.llama.llmgateway.repository.ModelCapabilityRepository;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public InternalLlmGatewayController(LlmGatewayService llmGatewayService, ModelCapabilityRepository modelCapabilityRepository) {
        this.llmGatewayService = llmGatewayService;
        this.modelCapabilityRepository = modelCapabilityRepository;
    }

    @PostMapping("/tenants/{tenantId}/chat")
    public ChatResponse chat(
            @PathVariable String tenantId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ChatRequest request
    ) {
        return llmGatewayService.chat(tenantId, idempotencyKey, request);
    }

    /** Tenant-agnostic in today's implementation (capability rows aren't tenant-scoped), so no
     * {@code tenantId} segment -- matches {@code ModelCapabilityRepository}'s own shape. */
    @GetMapping("/models/{modelId}/capabilities")
    public List<ModelCapabilityView> modelCapabilities(@PathVariable String modelId) {
        return modelCapabilityRepository.findByModelId(modelId).stream()
                .map(c -> new ModelCapabilityView(c.getCapabilityKey(), c.getStrength()))
                .toList();
    }
}
