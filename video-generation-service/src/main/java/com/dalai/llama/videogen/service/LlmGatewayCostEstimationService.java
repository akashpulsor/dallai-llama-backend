package com.dalai.llama.videogen.service;

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

    public LlmGatewayCostEstimationService(LlmGatewayClient llmGatewayClient) {
        this.llmGatewayClient = llmGatewayClient;
    }

    @Override
    public CostEstimate estimate(String prompt, String modelId) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        LlmGatewayEstimateResponse response = llmGatewayClient.estimate(
                tenantId.toString(),
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", prompt)), Map.of(), null, null)
        );
        BigDecimal cost = response == null || response.estimatedCost() == null ? BigDecimal.ZERO : response.estimatedCost();
        return new CostEstimate(cost, "USD");
    }
}
