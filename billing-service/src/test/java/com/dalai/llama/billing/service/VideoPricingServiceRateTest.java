package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.VideoPricingConfig;
import com.dalai.llama.billing.repository.VideoPricingConfigRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoPricingServiceRateTest {

    private final VideoPricingConfigRepository config = mock(VideoPricingConfigRepository.class);
    private final VideoPricingService pricing = VideoPricingFixture.withDefaults(config);

    @Test
    void anOpsSetRateRepricesANewBrief() {
        // ~1047 for a minute: 13.6835/s video + the per-shot and flat components at the defaults.
        when(config.findById(VideoPricingConfig.SINGLETON_ID)).thenReturn(Optional.of(
                new VideoPricingConfig(VideoPricingConfig.SINGLETON_ID, new BigDecimal("13.6835"), Instant.now())));

        VideoPricingService.PricePreview preview = pricing.preview(UUID.randomUUID(), 60);

        assertThat(preview.ratePerSecondInr()).isEqualByComparingTo("13.6835");
        assertThat(preview.quote().platformCost()).isEqualByComparingTo("1047.00");
        assertThat(preview.production().videoProduction()).isEqualByComparingTo("1047.00");
        assertThat(preview.production().total()).isEqualByComparingTo(preview.quote().totalPrice());
        assertThat(preview.production().musicProduction())
                .isEqualByComparingTo(preview.quote().totalPrice().subtract(new BigDecimal("1047.00")));
    }

    @Test
    void withNoSavedRateTheConfiguredDefaultApplies() {
        assertThat(pricing.currentRatePerSecond()).isEqualByComparingTo("100");
        assertThat(pricing.quote(UUID.randomUUID(), 60).platformCost()).isEqualByComparingTo("6225.99");
    }

    @Test
    void refusesANonPositiveRate() {
        assertThatThrownBy(() -> pricing.updateRatePerSecond(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
