package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.LanguageDeliveryMode;
import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import com.dalai.llama.llmgateway.domain.entity.ProviderLanguageMapping;
import com.dalai.llama.llmgateway.dto.LanguageSelection;
import com.dalai.llama.llmgateway.repository.LanguageMasterRepository;
import com.dalai.llama.llmgateway.repository.ProviderLanguageMappingRepository;
import com.dalai.llama.llmgateway.service.provider.ProviderLanguageDirective;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Resolves the platform language DTO into a typed provider directive after model routing. */
@Service
public class ProviderLanguageResolver {

    private final LanguageMasterRepository languageMasterRepository;
    private final ProviderLanguageMappingRepository mappingRepository;

    public ProviderLanguageResolver(LanguageMasterRepository languageMasterRepository,
                                    ProviderLanguageMappingRepository mappingRepository) {
        this.languageMasterRepository = languageMasterRepository;
        this.mappingRepository = mappingRepository;
    }

    public ProviderLanguageDirective resolve(String providerId, String modelId, LanguageSelection selection) {
        if (selection == null) {
            return null;
        }

        LanguageMaster language = resolveLanguage(selection);
        ProviderLanguageMapping mapping = mappingRepository
                .findByProviderIdAndLanguageCodeAndActiveTrue(providerId, language.getLanguageCode())
                .stream()
                .filter(candidate -> candidate.appliesTo(modelId))
                .max(Comparator.comparing(ProviderLanguageMapping::isModelSpecific))
                .orElseThrow(() -> GatewayException.badRequest(
                        "No active provider language mapping for provider_id=%s model_id=%s language=%s"
                                        .formatted(providerId, modelId, language.getLanguageCode())));

        if (mapping.getDeliveryMode() == LanguageDeliveryMode.OMIT) {
            return ProviderLanguageDirective.omit(language.getLanguageCode());
        }
        if (mapping.getProviderParameterName() == null || mapping.getProviderLanguageCode() == null) {
            throw new IllegalStateException("Invalid PARAMETER language mapping id=" + mapping.getMappingId());
        }
        return new ProviderLanguageDirective(language.getLanguageCode(), mapping.getDeliveryMode(),
                mapping.getProviderParameterName(), mapping.getProviderLanguageCode());
    }

    private LanguageMaster resolveLanguage(LanguageSelection selection) {
        String code = selection.code().trim().toLowerCase(Locale.ROOT);
        String script = normalizeScript(selection.script());
        String region = normalizeRegion(selection.region());
        List<LanguageMaster> candidates = languageMasterRepository.findByPlatformCode(code);
        if (candidates.isEmpty()) {
            throw GatewayException.badRequest("Unknown platform language code=" + code);
        }

        if (script == null && region == null) {
            return candidates.stream()
                    .filter(candidate -> Boolean.TRUE.equals(candidate.getDefaultLanguage()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No default canonical language for platform code=" + code));
        }

        return candidates.stream()
                .filter(candidate -> script == null || script.equals(candidate.getScriptCode()))
                .filter(candidate -> region == null || region.equals(candidate.getRegionCode()))
                .findFirst()
                .orElseThrow(() -> GatewayException.badRequest(
                        "No canonical language for code=%s script=%s region=%s".formatted(code, script, region)));
    }

    private String normalizeScript(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }

    private String normalizeRegion(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
