package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.CurrencyConversionService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
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
 * <p>Rates only. The conversion itself is arithmetic the caller can do; what it cannot do is
 * invent the rate billing will actually charge at.
 */
@RestController
@RequestMapping("/api/v1/internal/currency")
@RequiredArgsConstructor
@Hidden
public class InternalCurrencyController {

    private final CurrencyConversionService currencyConversionService;

    /** {@code {"defaultCurrency":"INR","rates":{"INR_INR":1,"USD_INR":95}}} -- keys are
     * {@code SOURCE_TARGET}. */
    @GetMapping("/rates")
    public ResponseEntity<CurrencyRatesResponse> rates() {
        return ResponseEntity.ok(new CurrencyRatesResponse(
                CurrencyConversionService.DEFAULT_CURRENCY,
                currencyConversionService.rates()));
    }

    public record CurrencyRatesResponse(String defaultCurrency, Map<String, BigDecimal> rates) {}
}
