package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.TenantServiceClient;
import com.dalai.llama.billing.repository.VideoPricingConfigRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;

/** A VideoPricingService on the application.yml defaults -- the real rates, not a mock, so a
 * pricing test fails when the arithmetic changes. */
final class VideoPricingFixture {

    private VideoPricingFixture() {
    }

    static VideoPricingService withDefaults() {
        return withDefaults(mock(VideoPricingConfigRepository.class));
    }

    static VideoPricingService withDefaults(VideoPricingConfigRepository config) {
        VideoPricingService service = new VideoPricingService(mock(TenantServiceClient.class), config);
        ReflectionTestUtils.setField(service, "baseRatePerSecond", new BigDecimal("100"));
        ReflectionTestUtils.setField(service, "defaultCreatorMarginPercent", new BigDecimal("12"));
        ReflectionTestUtils.setField(service, "currency", "INR");
        ReflectionTestUtils.setField(service, "secondsPerShot", 4);
        ReflectionTestUtils.setField(service, "imagesPerShot", 4);
        ReflectionTestUtils.setField(service, "imageCostPerImageInr", new BigDecimal("3.68"));
        ReflectionTestUtils.setField(service, "visionAnalysisCostPerShotInr", new BigDecimal("0.10"));
        ReflectionTestUtils.setField(service, "critiqueCostPerShotInr", new BigDecimal("0.11"));
        ReflectionTestUtils.setField(service, "scriptGenerationCostInr", new BigDecimal("0.77"));
        ReflectionTestUtils.setField(service, "screenplayGenerationCostInr", new BigDecimal("1.27"));
        return service;
    }
}
