package com.dalai.llama.videogen.service.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Converts a provider's USD price into the currency the creator is actually billed in.
 *
 * <p>The rate comes from billing-service, which owns it, rather than being configured here as
 * well: two services holding their own copy of an exchange rate is how a quoted estimate and the
 * debit that follows it quietly stop agreeing.
 *
 * <p>Cached, because a prepare batch quotes every shot in a project and the rate does not move
 * between them. A fetch failure leaves the amount in its source currency rather than guessing --
 * a USD figure labelled USD is honest; a rupee figure derived from a made-up rate is not.
 */
@Slf4j
@Component
public class BillingCurrencyClient {

    private final WebClient webClient;
    private final long timeoutMs;
    private final Duration cacheTtl;

    private volatile CachedRates cached;

    public BillingCurrencyClient(
            WebClient.Builder webClientBuilder,
            @Value("${video-gen.billing.base-url}") String baseUrl,
            @Value("${video-gen.billing.timeout-ms:5000}") long timeoutMs,
            @Value("${video-gen.billing.rates-cache-seconds:300}") long cacheSeconds
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
        this.cacheTtl = Duration.ofSeconds(cacheSeconds);
    }

    /** The currency amounts should be shown in -- billing's wallet currency. */
    public String displayCurrency() {
        CurrencyRatesResponse rates = ratesOrNull();
        return rates == null || rates.defaultCurrency() == null ? null : rates.defaultCurrency();
    }

    /** Converts into billing's wallet currency, or returns {@code amount} unchanged when the rate
     * is unavailable -- the caller keeps {@code sourceCurrency} as the label in that case. */
    public Converted convert(BigDecimal amount, String sourceCurrency) {
        if (amount == null) {
            return new Converted(null, sourceCurrency);
        }
        CurrencyRatesResponse rates = ratesOrNull();
        if (rates == null || rates.defaultCurrency() == null || rates.rates() == null) {
            return new Converted(amount, sourceCurrency);
        }
        String target = rates.defaultCurrency();
        if (target.equalsIgnoreCase(sourceCurrency)) {
            return new Converted(amount, target);
        }
        BigDecimal rate = rates.rates().get(sourceCurrency.toUpperCase() + "_" + target.toUpperCase());
        if (rate == null) {
            log.warn("No billing rate for {}_{} -- quoting in {}", sourceCurrency, target, sourceCurrency);
            return new Converted(amount, sourceCurrency);
        }
        return new Converted(amount.multiply(rate).setScale(2, RoundingMode.HALF_UP), target);
    }

    /** What the wallet will actually lose: the provider's price converted into billing's
     * currency and then marked up by the same margin billing debits at.
     *
     * <p>{@link #convert} alone is not a price. Quoting its output showed a creator INR 26.11
     * for a render that then took INR 48.31, because the margin was applied at debit time and
     * nowhere else. Both numbers come from billing in one response, so the quote and the
     * charge move together whatever the margin is set to.
     *
     * <p>Margin missing (billing unreachable, or an older billing that does not send it) means
     * the converted figure is returned unchanged -- under-quoting is bad, but inventing a
     * margin here would recreate the second-copy problem this exists to avoid. */
    public Converted price(BigDecimal amount, String sourceCurrency) {
        Converted converted = convert(amount, sourceCurrency);
        if (converted.amount() == null) {
            return converted;
        }
        CurrencyRatesResponse rates = ratesOrNull();
        BigDecimal marginPercent = rates == null ? null : rates.llmUsageMarginPercent();
        if (marginPercent == null) {
            log.warn("No usage margin from billing -- quoting the unmarked-up cost");
            return converted;
        }
        BigDecimal multiplier = BigDecimal.valueOf(100).add(marginPercent)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return new Converted(
                converted.amount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP),
                converted.currency());
    }

    private CurrencyRatesResponse ratesOrNull() {
        CachedRates current = cached;
        if (current != null && current.fetchedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return current.rates();
        }
        try {
            CurrencyRatesResponse fetched = webClient.get()
                    .uri("/api/v1/internal/currency/rates")
                    .retrieve()
                    .bodyToMono(CurrencyRatesResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
            if (fetched != null) {
                cached = new CachedRates(fetched, Instant.now());
            }
            return fetched;
        } catch (RuntimeException ex) {
            // Quoting is not worth failing a prepare over. Serve a stale rate if we have one --
            // a rate from five minutes ago beats no price at all.
            log.warn("Could not fetch billing currency rates: {}", ex.getMessage());
            return current == null ? null : current.rates();
        }
    }

    public record Converted(BigDecimal amount, String currency) {}

    public record CurrencyRatesResponse(String defaultCurrency, Map<String, BigDecimal> rates,
                                        BigDecimal llmUsageMarginPercent) {}

    private record CachedRates(CurrencyRatesResponse rates, Instant fetchedAt) {}
}
