package com.dalai.llama.billing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one owner of billing's currency rates.
 *
 * <p>Providers quote in USD and wallets are held in INR, so something has to convert. That
 * conversion used to live privately inside {@code UsageServiceImpl}, which was fine while the
 * debit was the only place it happened -- but the cost estimate shown to a creator before they
 * approve a render is the same number, and video-generation-service had no way to ask for the
 * rate. The result was a dollar figure on the shot card sitting next to a rupee wallet balance.
 *
 * <p>Exposed over {@code /api/v1/internal/currency/rates} rather than copied into the other
 * service: two services holding their own copy of an exchange rate is how an estimate and the
 * debit that follows it quietly stop agreeing.
 */
@Slf4j
@Service
public class CurrencyConversionService {

    public static final String DEFAULT_CURRENCY = "INR";

    @Value("${billing.currency.conversion-rates:INR_INR=1,USD_INR=95}")
    private String conversionRatesConfig;

    /** Parsed {@code SOURCE_TARGET -> rate} pairs, e.g. {@code USD_INR -> 95}. */
    public Map<String, BigDecimal> rates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("INR_INR", BigDecimal.ONE);
        if (conversionRatesConfig == null || conversionRatesConfig.isBlank()) {
            return rates;
        }
        for (String entry : conversionRatesConfig.split("[,;]")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split("[:=]", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                continue;
            }
            rates.put(parts[0].trim().toUpperCase(Locale.ROOT), new BigDecimal(parts[1].trim()));
        }
        return rates;
    }

    public BigDecimal convert(BigDecimal amount, String sourceCurrency, String targetCurrency, int scale) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        String source = normalize(sourceCurrency);
        String target = normalize(targetCurrency);
        if (source.equals(target)) {
            return amount.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal rate = rates().get(source + "_" + target);
        if (rate == null) {
            throw new IllegalArgumentException(
                    "No billing currency conversion rate configured for " + source + "_" + target);
        }
        return amount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    public String normalize(String currency) {
        return currency == null || currency.isBlank()
                ? DEFAULT_CURRENCY
                : currency.trim().toUpperCase(Locale.ROOT);
    }
}
