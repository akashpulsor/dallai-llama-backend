package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.domain.LanguageDeliveryMode;

/** A typed, resolved provider-specific language instruction. */
public record ProviderLanguageDirective(
        String canonicalLanguageCode,
        LanguageDeliveryMode deliveryMode,
        String providerParameterName,
        String providerLanguageCode
) {
    public static ProviderLanguageDirective omit(String canonicalLanguageCode) {
        return new ProviderLanguageDirective(canonicalLanguageCode, LanguageDeliveryMode.OMIT, null, null);
    }

    public boolean shouldSendToProvider() {
        return deliveryMode == LanguageDeliveryMode.PARAMETER
                && providerParameterName != null && !providerParameterName.isBlank()
                && providerLanguageCode != null && !providerLanguageCode.isBlank();
    }
}
