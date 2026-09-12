package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.LanguageDeliveryMode;
import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import com.dalai.llama.llmgateway.domain.entity.ProviderLanguageMapping;
import com.dalai.llama.llmgateway.dto.LanguageSelection;
import com.dalai.llama.llmgateway.repository.LanguageMasterRepository;
import com.dalai.llama.llmgateway.repository.ProviderLanguageMappingRepository;
import com.dalai.llama.llmgateway.service.provider.ProviderLanguageDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ProviderLanguageResolverTest {

    private final LanguageMasterRepository languageMasterRepository = mock(LanguageMasterRepository.class);
    private final ProviderLanguageMappingRepository mappingRepository = mock(ProviderLanguageMappingRepository.class);
    private final ProviderLanguageResolver resolver = new ProviderLanguageResolver(languageMasterRepository, mappingRepository);

    @Test
    void resolvesBarePlatformCodeToTheCanonicalDefaultAndOmitsForElevenLabs() {
        LanguageMaster hindiIndia = language("hi-IN", "hi", "Deva", "IN", true);
        given(languageMasterRepository.findByPlatformCode("hi")).willReturn(List.of(hindiIndia));
        given(mappingRepository.findByProviderIdAndLanguageCodeAndActiveTrue("elevenlabs", "hi-IN"))
                .willReturn(List.of(mapping(null, LanguageDeliveryMode.OMIT, null, null)));

        ProviderLanguageDirective directive = resolver.resolve(
                "elevenlabs", "elevenlabs-tts-v1", new LanguageSelection("hi", null, null));

        assertThat(directive.canonicalLanguageCode()).isEqualTo("hi-IN");
        assertThat(directive.deliveryMode()).isEqualTo(LanguageDeliveryMode.OMIT);
        assertThat(directive.shouldSendToProvider()).isFalse();
    }

    @Test
    void usesTheExactModelMappingAndNeverAnUnrelatedModelsMapping() {
        LanguageMaster hindiIndia = language("hi-IN", "hi", "Deva", "IN", true);
        given(languageMasterRepository.findByPlatformCode("hi")).willReturn(List.of(hindiIndia));
        given(mappingRepository.findByProviderIdAndLanguageCodeAndActiveTrue("provider-a", "hi-IN"))
                .willReturn(List.of(
                        mapping(null, LanguageDeliveryMode.PARAMETER, "language_code", "hi"),
                        mapping("another-model", LanguageDeliveryMode.OMIT, null, null),
                        mapping("target-model", LanguageDeliveryMode.OMIT, null, null)
                ));

        ProviderLanguageDirective directive = resolver.resolve(
                "provider-a", "target-model", new LanguageSelection("hi", null, null));

        assertThat(directive.deliveryMode()).isEqualTo(LanguageDeliveryMode.OMIT);
        assertThat(directive.shouldSendToProvider()).isFalse();
    }

    private static LanguageMaster language(String canonicalCode, String platformCode, String scriptCode,
                                           String regionCode, boolean defaultLanguage) {
        return LanguageMaster.builder()
                .languageCode(canonicalCode)
                .platformCode(platformCode)
                .scriptCode(scriptCode)
                .regionCode(regionCode)
                .defaultLanguage(defaultLanguage)
                .displayName(canonicalCode)
                .build();
    }

    private static ProviderLanguageMapping mapping(String modelId, LanguageDeliveryMode deliveryMode,
                                                   String parameterName, String providerLanguageCode) {
        return ProviderLanguageMapping.builder()
                .providerId("provider-a")
                .modelId(modelId)
                .languageCode("hi-IN")
                .deliveryMode(deliveryMode)
                .providerParameterName(parameterName)
                .providerLanguageCode(providerLanguageCode)
                .active(true)
                .build();
    }
}