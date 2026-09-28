package com.dalai.llama.llmgateway.service.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wan's prompt budget.
 *
 * <p>This is not a cosmetic number. Anything composed above it is handed to the compression model
 * to be SHORTENED, and a compressor drops specifics first -- the lighting, lens and movement
 * detail the shot plan exists to carry. Hardcoded at 1000 against a real limit of 20000, that
 * meant every Wan prompt of any substance was rewritten down to a fifth of a page before it ever
 * reached the video model.
 */
class WanPromptStrategyLimitTest {

    private WanPromptStrategy strategy(int configured) {
        return new WanPromptStrategy(mock(NegativePromptComposer.class), configured);
    }

    @Test
    void wanGetsItsRealTwentyThousandCharacterBudget() {
        assertThat(strategy(20000).maxPromptLength()).isEqualTo(20000);
    }

    @Test
    void theBudgetIsConfigurableSoACorrectedProviderLimitNeedsNoRebuild() {
        assertThat(strategy(12000).maxPromptLength()).isEqualTo(12000);
    }

    @Test
    void onlyAlibabaWanModelsUseThisStrategy() {
        WanPromptStrategy wan = strategy(20000);
        assertThat(wan.supports("alibaba/wan-3.0-prime")).isTrue();
        assertThat(wan.supports("bytedance/seedance-1.0")).isFalse();
        assertThat(wan.supports(null)).isFalse();
    }
}
