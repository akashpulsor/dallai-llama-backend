package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.billing.BillingCurrencyClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayEstimateResponse;
import com.dalai.llama.videogen.web.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LlmGatewayCostEstimationService implements CostEstimationService {

    private final LlmGatewayClient llmGatewayClient;
    private final BillingCurrencyClient billingCurrencyClient;

    public LlmGatewayCostEstimationService(LlmGatewayClient llmGatewayClient,
                                           BillingCurrencyClient billingCurrencyClient) {
        this.llmGatewayClient = llmGatewayClient;
        this.billingCurrencyClient = billingCurrencyClient;
    }

    @Override
    public CostEstimate estimate(String prompt, String modelId, Integer durationSeconds) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        // duration_seconds is the param name fal.ai itself uses, and the one llm-gateway's
        // computeCost reads to price a duration-priced model. Without it a video estimate is
        // priced off input tokens, whose rate is 0 for these models -- which is why every
        // estimate came back as zero.
        Map<String, Object> params = durationSeconds == null
                ? Map.of()
                : Map.of("duration_seconds", durationSeconds);
        LlmGatewayEstimateResponse response = llmGatewayClient.estimate(
                tenantId.toString(),
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", prompt)), params, null, null)
        );
        BigDecimal cost = response == null || response.estimatedCost() == null ? BigDecimal.ZERO : response.estimatedCost();
        // Providers quote in USD; the wallet this will be charged against is in billing's own
        // currency, and billing marks usage up before it debits. price() applies both from one
        // billing response, so this quotes what the wallet will actually lose.
        //
        // It used to convert only. That quoted the raw provider cost -- INR 26.11 on a render
        // that then took INR 48.31 -- because the margin lived at debit time and nowhere else.
        // An estimate that is not the charge is worse than no estimate: it is a number the
        // creator budgets against and is then wrong about.
        BillingCurrencyClient.Converted priced = billingCurrencyClient.price(cost, "USD");
        return new CostEstimate(priced.amount(), priced.currency());
    }
}
