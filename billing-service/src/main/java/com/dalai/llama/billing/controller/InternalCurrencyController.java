package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.CurrencyConversionService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Billing's exchange rates, for services that must show a cost before billing takes one.
 *
 * <p>video-generation-service quotes a render's cost to the creator at prepare time, from a
 * provider price in USD, while the wallet the charge lands in is INR. Without this it either
 * showed dollars next to a rupee balance or kept its own copy of the rate -- and a second copy of
 * an exchange rate is how an estimate and the debit that follows it quietly stop agreeing.
 *
 * <p>Rates and the usage margin -- the two numbers a caller needs to quote what the wallet will
 * actually lose. The arithmetic is the caller's; what it cannot do is invent the figures billing
 * will charge at. Margin is served here for exactly the reason the rate is: an estimate that
 * converted correctly but omitted margin quoted a creator INR 26.11 for a render that then took
 * INR 48.31 out of their wallet.
 */
@RestController
@RequestMapping("/api/v1/internal/currency")
@Hidden
public class InternalCurrencyController {

    private final CurrencyConversionService currencyConversionService;

    /** The same margin {@code LlmBillingEventConsumer} applies when it debits, read from the
     * same property, so a quote built from this cannot drift from the charge that follows. */
    private final java.math.BigDecimal llmUsageMarginPercent;

    public InternalCurrencyController(
            CurrencyConversionService currencyConversionService,
            @org.springframework.beans.factory.annotation.Value("${billing.llm-usage-margin-percent:85}")
            java.math.BigDecimal llmUsageMarginPercent
    ) {
        this.currencyConversionService = currencyConversionService;
        this.llmUsageMarginPercent = llmUsageMarginPercent;
    }

    /** {@code {"defaultCurrency":"INR","rates":{"INR_INR":1,"USD_INR":95},
     * "llmUsageMarginPercent":85}} -- rate keys are {@code SOURCE_TARGET}. Multiply a converted
     * provider cost by {@code (100 + llmUsageMarginPercent) / 100} to get what the wallet loses. */
    @GetMapping("/rates")
    public ResponseEntity<CurrencyRatesResponse> rates() {
        return ResponseEntity.ok(new CurrencyRatesResponse(
                CurrencyConversionService.DEFAULT_CURRENCY,
                currencyConversionService.rates(),
                llmUsageMarginPercent));
    }

    public record CurrencyRatesResponse(String defaultCurrency, Map<String, BigDecimal> rates,
                                        BigDecimal llmUsageMarginPercent) {}
}
