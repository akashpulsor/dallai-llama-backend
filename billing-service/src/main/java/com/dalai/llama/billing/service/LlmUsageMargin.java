package com.dalai.llama.billing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The one markup billing puts on raw LLM provider cost before the wallet is debited -- shared by
 * {@code LlmBillingEventConsumer} (the debit) and {@code InternalCurrencyController} (the rate
 * other services estimate with), so a quote can never drift from the charge that follows. */
@Component
public class LlmUsageMargin {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal percent;

    public LlmUsageMargin(@Value("${billing.llm-usage-margin-percent:20}") BigDecimal percent) {
        this.percent = percent;
    }

    public BigDecimal percent() {
        return percent;
    }

    public BigDecimal applyTo(BigDecimal rawCost) {
        return rawCost.multiply(HUNDRED.add(percent)).divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
