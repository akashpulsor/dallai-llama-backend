package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.entity.ModelMaster;
import com.dalai.llama.llmgateway.domain.entity.Provider;
import com.dalai.llama.llmgateway.domain.entity.RateCard;
import com.dalai.llama.llmgateway.domain.entity.TenantModelOverride;
import com.dalai.llama.llmgateway.repository.ModelMasterRepository;
import com.dalai.llama.llmgateway.repository.ProviderRepository;
import com.dalai.llama.llmgateway.repository.RateCardRepository;
import com.dalai.llama.llmgateway.repository.TenantModelOverrideRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Doc §6: model_master + rate_card is the single source of truth the router, rate limiter, and
 * billing all read from -- never hardcode per-model behaviour in code. Doc §11: entitlement
 * (tenant_model_override.allowed) is layered on top of the model defaults, default-allow when
 * no override row exists for a tenant+model pair.
 */
@Service
public class ModelRouterService {

    private final ModelMasterRepository modelMasterRepository;
    private final RateCardRepository rateCardRepository;
    private final TenantModelOverrideRepository tenantModelOverrideRepository;
    private final ProviderRepository providerRepository;
    private final int defaultMaxConcurrentPerProvider;

    public ModelRouterService(
            ModelMasterRepository modelMasterRepository,
            RateCardRepository rateCardRepository,
            TenantModelOverrideRepository tenantModelOverrideRepository,
            ProviderRepository providerRepository,
            @Value("${llm-gateway.default-max-concurrent-per-provider}") int defaultMaxConcurrentPerProvider
    ) {
        this.modelMasterRepository = modelMasterRepository;
        this.rateCardRepository = rateCardRepository;
        this.tenantModelOverrideRepository = tenantModelOverrideRepository;
        this.providerRepository = providerRepository;
        this.defaultMaxConcurrentPerProvider = defaultMaxConcurrentPerProvider;
    }

    public RoutedModel route(String tenantId, String modelId) {
        ModelMaster model = modelMasterRepository.findById(modelId)
                .orElseThrow(() -> GatewayException.notFound("Unknown model_id: " + modelId));
        if (!"active".equalsIgnoreCase(model.getStatus())) {
            throw GatewayException.badRequest("model_id=%s is not active (status=%s)".formatted(modelId, model.getStatus()));
        }

        Optional<TenantModelOverride> override = tenantModelOverrideRepository.findByIdTenantIdAndIdModelId(tenantId, modelId);
        if (override.isPresent() && Boolean.FALSE.equals(override.get().getAllowed())) {
            throw GatewayException.forbidden("tenant_id=%s is not entitled to model_id=%s".formatted(tenantId, modelId));
        }

        RateCard rateCard = rateCardRepository
                .findFirstByModelIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(modelId, OffsetDateTime.now())
                .orElseThrow(() -> GatewayException.notFound("No active rate_card for model_id=" + modelId));

        return new RoutedModel(model, rateCard);
    }

    /** Per-tenant RPM override for this model, if the tenant has one configured. */
    public Optional<Integer> tenantRpmOverride(String tenantId, String modelId) {
        return tenantModelOverrideRepository.findByIdTenantIdAndIdModelId(tenantId, modelId)
                .map(TenantModelOverride::getRpmOverride)
                .filter(v -> v != null);
    }

    /** Concurrency cap for the provider behind this routed model -- i.e. how many calls to this
     * provider account may be simultaneously in-flight (blocked) at once, as opposed to the
     * RPM/TPM bucket which only caps how fast NEW calls are admitted. Resolution order: a
     * tenant's own tier/plan override (tenant_model_override.max_concurrent_override, the
     * "credits/tier"-driven knob) beats the provider account's own default cap
     * (provider.max_concurrent), which beats a global config fallback for providers that have
     * never had a cap configured. */
    public int effectiveMaxConcurrent(String tenantId, RoutedModel routed) {
        Optional<Integer> tenantOverride = tenantModelOverrideRepository
                .findByIdTenantIdAndIdModelId(tenantId, routed.model().getModelId())
                .map(TenantModelOverride::getMaxConcurrentOverride)
                .filter(v -> v != null);
        if (tenantOverride.isPresent()) {
            return tenantOverride.get();
        }
        Integer providerCap = providerRepository.findById(routed.model().getProviderId())
                .map(Provider::getMaxConcurrent)
                .orElse(null);
        return providerCap != null ? providerCap : defaultMaxConcurrentPerProvider;
    }

    /** Doc §11/§16: tenant-facing listing, filtered by entitlement before type -- a model this
     * tenant has been explicitly disallowed never appears, even if it matches the type filter. */
    public java.util.List<ModelMaster> listForTenant(String tenantId, String type) {
        java.util.List<ModelMaster> models = (type == null || type.isBlank())
                ? modelMasterRepository.findByStatus("active")
                : modelMasterRepository.findByTypeAndStatus(type, "active");
        return models.stream()
                .filter(model -> tenantModelOverrideRepository.findByIdTenantIdAndIdModelId(tenantId, model.getModelId())
                        .map(TenantModelOverride::getAllowed)
                        .orElse(true))
                .toList();
    }
}
