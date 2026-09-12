package com.dalai.llama.videogen.service.llmgateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGatewayLanguageSelectionTest {

    @Test
    void convertsPersistedLanguageTagToPlatformLanguageSelection() {
        assertThat(LlmGatewayLanguageSelection.fromBcp47String("hi-IN"))
                .isEqualTo(new LlmGatewayLanguageSelection("hi", null, "IN"));
    }

    @Test
    void preservesAnExplicitScriptSubtag() {
        assertThat(LlmGatewayLanguageSelection.fromBcp47String("hi-Latn-IN"))
                .isEqualTo(new LlmGatewayLanguageSelection("hi", "Latn", "IN"));
    }
}