package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.LlmGatewayRateClient;
import com.dalai.llama.billing.client.LlmGatewayRateClient.Rate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The new brief form showed "Your cost estimate: INR 3121.48" for 30s -- the ₹100/second fallback,
 * nothing to do with what a video costs. The range now prices the video at the model's real rates.
 */
class ProductionCostEstimateServiceTest {

    private static final String SEEDANCE = "bytedance/seedance-2.0/fast";
    private final VideoPricingService pricing = mock(VideoPricingService.class);
    private final LlmGatewayRateClient rates = mock(LlmGatewayRateClient.class);
    private final CurrencyConversionService fx = mock(CurrencyConversionService.class);
    private final ProductionCostEstimateService service = new ProductionCostEstimateService(pricing, rates, fx, SEEDANCE, "INR");

    @BeforeEach
    void setUp() {
        when(pricing.callCostsExceptVideo(30)).thenReturn(new BigDecimal("125.00"));
        when(pricing.currentRatePerSecond()).thenReturn(new BigDecimal("100"));
        when(fx.convert(any(), anyString(), anyString(), anyInt())).thenAnswer(call -> {
            BigDecimal amount = call.getArgument(0);
            return "USD".equals(call.getArgument(1)) ? amount.multiply(BigDecimal.valueOf(95)).setScale(6, RoundingMode.HALF_UP) : amount;
        });
    }

    private static Rate rate(String resolution, String usdPerSecond) {
        return new Rate(SEEDANCE, resolution, new BigDecimal(usdPerSecond), BigDecimal.ZERO, BigDecimal.ZERO, "USD");
    }

    @Test
    void aThirtySecondVideoIsPricedAtTheModelsCheapestAndDearestTier() {
        when(rates.current(SEEDANCE)).thenReturn(List.of(rate("720p", "0.24192"), rate("480p", "0.1076"), rate(null, "0.24192")));

        ProductionCostEstimateService.Estimate estimate = service.estimate(30);

        // 125 + 30 x 0.1076 x 95 = 431.66 -> 430;  125 + 30 x 0.24192 x 95 = 814.47 -> 820
        assertThat(estimate.low()).isEqualByComparingTo("430");
        assertThat(estimate.high()).isEqualByComparingTo("820");
        assertThat(estimate.currency()).isEqualTo("INR");
    }

    @Test
    void withoutReachableRatesTheOpsRateStandsIn() {
        when(rates.current(SEEDANCE)).thenThrow(new RuntimeException("connection refused"));

        ProductionCostEstimateService.Estimate estimate = service.estimate(30);

        assertThat(estimate.low()).isEqualByComparingTo("3120");
        assertThat(estimate.high()).isEqualByComparingTo("3130");
    }
}
