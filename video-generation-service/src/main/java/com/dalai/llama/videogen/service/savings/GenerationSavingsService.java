package com.dalai.llama.videogen.service.savings;

import com.dalai.llama.videogen.dto.GenerationSavingsView;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.service.billing.BillingCurrencyClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient.RateCard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The project's {@link GenerationSavings}, priced the way the wallet is charged. */
@Service
@RequiredArgsConstructor
public class GenerationSavingsService {

    /** What a clip shorter than its plan is slowed with (post-production's AI frames). */
    static final String SLOW_DOWN_MODEL = "topaz/interpolate/video";

    private final ShotGenerationOrchestrator orchestrator;
    private final LlmGatewayClient llmGatewayClient;
    private final BillingCurrencyClient billingCurrencyClient;

    public GenerationSavingsView forProject(UUID tenantId, UUID projectId) {
        Map<String, List<RateCard>> rates = new HashMap<>();
        GenerationSavings.Totals totals = GenerationSavings.of(
                orchestrator.currentJobPerShot(tenantId, projectId),
                model -> rates.computeIfAbsent(model, llmGatewayClient::rateCards),
                llmGatewayClient.rateCards(SLOW_DOWN_MODEL));

        // Rates are quoted in USD; price() converts and marks up exactly as the wallet is debited.
        BillingCurrencyClient.Converted planned = billingCurrencyClient.price(totals.planned(), "USD");
        BillingCurrencyClient.Converted made = billingCurrencyClient.price(totals.made(), "USD");
        BigDecimal saved = planned.amount().subtract(made.amount()).max(BigDecimal.ZERO);
        int savedPercent = planned.amount().signum() == 0 ? 0
                : saved.multiply(BigDecimal.valueOf(100)).divide(planned.amount(), 0, RoundingMode.HALF_UP).intValue();
        return new GenerationSavingsView(totals.shots(), totals.plannedSeconds(), totals.generatedSeconds(),
                planned.amount(), made.amount(), saved, savedPercent, planned.currency());
    }
}
