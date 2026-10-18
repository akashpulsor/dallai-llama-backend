package com.dalai.llama.tenant.leadmanagement.inquiry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The platform's price for a request sent to Dalaillama (no creator chosen), bound from
 * {@code brands.request-pricing}. A request sent to a creator uses the creator's own rate and
 * upfront share instead (V49). */
@ConfigurationProperties(prefix = "brands.request-pricing")
public record RequestPricingProperties(
        /* The price of a 60-second video; any other length costs the same per second. */
        BigDecimal platformMinutePrice,
        /* Share of the platform price the brand pays upfront. */
        int platformPrepaymentPercent
) {
    public BigDecimal platformPricePerSecond() {
        return platformMinutePrice.divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }
}
