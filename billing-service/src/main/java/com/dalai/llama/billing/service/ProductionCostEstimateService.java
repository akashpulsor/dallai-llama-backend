package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.LlmGatewayRateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * What a video of a given length tentatively costs to produce, as a range for the creator to price
 * from. The calls a video takes are the ones {@link VideoPricingService} already counts -- writing,
 * per-shot frames, analysis and critique -- and the video itself is priced at the video model's
 * real rate from llm-gateway's rate card: its cheapest tier (480p) at the low end, its dearest at
 * the high end. Not a price: the creator sets that, from the client's budget.
 */
@Slf4j
@Service
public class ProductionCostEstimateService {

    private static final BigDecimal TEN = BigDecimal.TEN;

    private final VideoPricingService videoPricingService;
    private final LlmGatewayRateClient rateClient;
    private final CurrencyConversionService currencyConversionService;
    private final String videoModelId;
    private final String currency;

    public ProductionCostEstimateService(
            VideoPricingService videoPricingService,
            LlmGatewayRateClient rateClient,
            CurrencyConversionService currencyConversionService,
            @Value("${billing.video-pricing.video-model-id:bytedance/seedance-2.0/fast}") String videoModelId,
            @Value("${billing.video-pricing.currency:INR}") String currency
    ) {
        this.videoPricingService = videoPricingService;
        this.rateClient = rateClient;
        this.currencyConversionService = currencyConversionService;
        this.videoModelId = videoModelId;
        this.currency = currency;
    }

    public Estimate estimate(int durationSeconds) {
        BigDecimal calls = videoPricingService.callCostsExceptVideo(durationSeconds);
        List<BigDecimal> perSecond = videoRatesPerSecond();
        BigDecimal seconds = BigDecimal.valueOf(durationSeconds);
        BigDecimal low = calls.add(perSecond.get(0).multiply(seconds)).divide(TEN, 0, RoundingMode.FLOOR).multiply(TEN);
        BigDecimal high = calls.add(perSecond.get(1).multiply(seconds)).divide(TEN, 0, RoundingMode.CEILING).multiply(TEN);
        return new Estimate(durationSeconds, low, high, currency);
    }

    /** [cheapest, dearest] per-second video rate in this service's currency. The ops-set rate
     * stands in only when the gateway has no rate for the model or cannot be reached. */
    private List<BigDecimal> videoRatesPerSecond() {
        try {
            List<BigDecimal> rates = rateClient.current(videoModelId).stream()
                    .filter(rate -> rate.perSecondCost() != null && rate.perSecondCost().signum() > 0)
                    .map(rate -> currencyConversionService.convert(rate.perSecondCost(),
                            Objects.requireNonNullElse(rate.currency(), "USD"), currency, 6))
                    .sorted()
                    .toList();
            if (!rates.isEmpty()) {
                return List.of(rates.get(0), rates.get(rates.size() - 1));
            }
            log.warn("No rate card for video model {}; estimating with the ops rate", videoModelId);
        } catch (Exception ex) {
            log.warn("Could not read video model rates for {}: {}", videoModelId, ex.getMessage());
        }
        BigDecimal ops = videoPricingService.currentRatePerSecond();
        return List.of(ops, ops);
    }

    public record Estimate(int durationSeconds, BigDecimal low, BigDecimal high, String currency) {}
}
