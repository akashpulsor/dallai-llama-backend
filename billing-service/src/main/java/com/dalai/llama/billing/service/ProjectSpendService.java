package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A project's real accumulated spend, and whether it's still within the price it was quoted at --
 * the per-project spend cap llm-gateway's {@code LlmGatewayService} checks (via {@code
 * BillingWalletClient}) before every dispatch. Real spend is summed
 * straight from {@code usage_records} (already written per real dispatch by {@code
 * LlmBillingEventConsumer}) rather than a second running total -- one ledger, not two that could
 * drift apart.
 */
@Service
@RequiredArgsConstructor
public class ProjectSpendService {

    private final UsageRecordRepository usageRecordRepository;
    private final CreativePlanningServiceClient creativePlanningServiceClient;

    @Value("${billing.project-spend-cap-multiple:1.5}")
    private BigDecimal capMultiple;

    @Transactional(readOnly = true)
    public BigDecimal totalSpent(UUID tenantId, UUID projectId) {
        return usageRecordRepository.sumCostByProjectId(tenantId, projectId);
    }

    /** No quoted price on record for this project (e.g. it didn't come through the
     * requirement/quote flow) -- the cap simply doesn't apply, same as it never having been
     * evaluated at all. Only a project with a real quoted price to measure against can be capped. */
    @Transactional(readOnly = true)
    public SpendStatus checkCap(UUID tenantId, UUID projectId) {
        BigDecimal quotedTotalPrice = creativePlanningServiceClient.getQuotedTotalPrice(tenantId, projectId);
        BigDecimal spent = totalSpent(tenantId, projectId);
        if (quotedTotalPrice == null || quotedTotalPrice.signum() <= 0) {
            return new SpendStatus(spent, null, true);
        }
        BigDecimal cap = quotedTotalPrice.multiply(capMultiple);
        return new SpendStatus(spent, quotedTotalPrice, spent.compareTo(cap) < 0);
    }

    public record SpendStatus(BigDecimal totalSpent, BigDecimal quotedTotalPrice, boolean withinCap) {}
}
