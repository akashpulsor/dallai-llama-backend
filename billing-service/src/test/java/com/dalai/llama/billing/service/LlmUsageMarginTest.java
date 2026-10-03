package com.dalai.llama.billing.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUsageMarginTest {

    @Test
    void addsTwentyPercentOnTopOfRawProviderCost() {
        LlmUsageMargin margin = new LlmUsageMargin(new BigDecimal("20"));

        assertThat(margin.applyTo(new BigDecimal("1.0000"))).isEqualByComparingTo("1.2000");
        assertThat(margin.applyTo(new BigDecimal("0.0123"))).isEqualByComparingTo("0.0148");
    }
}
