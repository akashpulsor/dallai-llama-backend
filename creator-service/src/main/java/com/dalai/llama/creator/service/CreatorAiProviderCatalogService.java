package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorAiProviderConfig;
import com.dalai.llama.creator.dto.response.CreatorAiProviderResponse;
import com.dalai.llama.creator.repository.CreatorAiProviderConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CreatorAiProviderCatalogService {

    private final CreatorAiProviderConfigRepository providerRepository;
    private final CreatorProperties properties;

    public CreatorAiProviderCatalogService(
            CreatorAiProviderConfigRepository providerRepository,
            CreatorProperties properties
    ) {
        this.providerRepository = providerRepository;
        this.properties = properties;
    }

    public List<CreatorAiProviderResponse> listVisibleProviders() {
        return providerRepository.findByActiveTrueAndVisibleTrueOrderBySortOrderAscDisplayNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public String resolveProviderCode() {
        String configured = normalize(properties.getAi().getProvider());
        if (!configured.isBlank()) {
            return configured;
        }
        return providerRepository.findFirstByActiveTrueAndDefaultProviderTrueOrderBySortOrderAsc()
                .map(CreatorAiProviderConfig::getCode)
                .map(this::normalize)
                .orElse("openai");
    }

    private CreatorAiProviderResponse toResponse(CreatorAiProviderConfig provider) {
        return new CreatorAiProviderResponse(
                provider.getId(),
                provider.getCode(),
                provider.getDisplayName(),
                provider.getDisplayName(),
                provider.getDescription(),
                provider.getProviderType(),
                provider.getDefaultModel(),
                provider.isDefaultProvider(),
                credentialConfigured(provider),
                provider.getSortOrder(),
                provider.getCapabilities() == null ? Map.of() : provider.getCapabilities()
        );
    }

    private boolean credentialConfigured(CreatorAiProviderConfig provider) {
        if ("mock".equalsIgnoreCase(provider.getCode())) {
            return true;
        }
        String key = provider.getCredentialEnvKey();
        if ("OPENAI_API_KEY".equalsIgnoreCase(key)) {
            return properties.getAi().getApiKey() != null && !properties.getAi().getApiKey().isBlank();
        }
        return key != null && !key.isBlank() && System.getenv(key) != null && !System.getenv(key).isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
