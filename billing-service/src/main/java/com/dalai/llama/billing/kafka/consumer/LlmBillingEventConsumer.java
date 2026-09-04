package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.domain.event.LlmBillingEvent;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.UsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** The billing-service half of llm-gateway's real-time usage reporting -- llm-gateway has always
 * published a {@link LlmBillingEvent} to {@code llm.billing.events} on every real dispatch (see
 * its {@code BillingEventPublisher}), but until now nothing consumed that topic, so a creator's
 * wallet was never actually debited for LLM/image usage in the live pre-production-service path
 * (the only real per-call debit logic that ever existed lived in the now-decommissioned
 * creator-service, see {@link CreatorBillingEventConsumer}'s sibling). This closes that gap: the
 * exact same log-then-debit primitive ({@link UsageService#recordBillableUsage}) that path uses,
 * reused here so the resulting line item shows up in the same wallet/usage history either way.
 *
 * <p>Margin is applied here, not in llm-gateway -- llm-gateway's own doc is explicit that it logs
 * raw per-call cost only and pricing/margin is billing-service's job. FAILED jobs report cost=0
 * (Gemini/the providers used here don't charge for a failed generation attempt), so those are
 * skipped rather than debiting nothing meaninglessly. */
@Slf4j
@Component
public class LlmBillingEventConsumer {

    private final UsageService usageService;
    private final BigDecimal marginPercent;

    public LlmBillingEventConsumer(
            UsageService usageService,
            @Value("${billing.llm-usage-margin-percent:85}") BigDecimal marginPercent
    ) {
        this.usageService = usageService;
        this.marginPercent = marginPercent;
    }

    @KafkaListener(
            topics = "${billing.kafka.llm-billing-events-topic:llm.billing.events}",
            groupId = "billing-service",
            containerFactory = "llmBillingEventListenerFactory"
    )
    public void consume(LlmBillingEvent event) {
        if (event.getEventId() == null || event.getJobId() == null) {
            log.warn("Skipping LLM billing event without eventId/jobId");
            return;
        }
        UUID tenantId = parseTenantId(event.getTenantId());
        if (tenantId == null) {
            log.warn("Skipping LLM billing event {} with non-UUID tenantId={}", event.getEventId(), event.getTenantId());
            return;
        }
        BigDecimal rawCost = event.getCost() == null ? BigDecimal.ZERO : event.getCost();
        if (rawCost.signum() <= 0) {
            // FAILED jobs (or any zero-cost dispatch) never reach here with anything to charge --
            // not an error, just nothing to bill.
            return;
        }

        BigDecimal billedCost = rawCost
                .multiply(BigDecimal.ONE.add(marginPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
        long totalTokens = Math.max(0, event.getInputTokens()) + Math.max(0, event.getOutputTokens());
        BigDecimal quantity = BigDecimal.valueOf(Math.max(totalTokens, 1));
        BigDecimal unitCost = billedCost.divide(quantity, 8, RoundingMode.HALF_UP);

        log.info(
                "WALLET_DEBIT_AUDIT_REQUEST source=LLM_GATEWAY eventId={} jobId={} tenantId={} model={} inputTokens={} outputTokens={} rawCost={} marginPercent={} billedCost={} currency={}",
                event.getEventId(), event.getJobId(), tenantId, event.getModelId(),
                event.getInputTokens(), event.getOutputTokens(), rawCost, marginPercent, billedCost, event.getCurrency()
        );

        usageService.recordBillableUsage(new BillableUsageRequest(
                tenantId,
                event.getProjectId(),
                UsageMetric.AI_LLM_TOKENS,
                quantity,
                BillingUnit.TOKEN,
                unitCost,
                billedCost,
                "LLM_GATEWAY",
                event.getJobId(),
                "LLM usage: " + event.getModelId(),
                null,
                event.getJobId().toString(),
                event.getCurrency() == null ? "USD" : event.getCurrency(),
                event.getCreatedAt() == null ? Instant.now() : event.getCreatedAt().toInstant()
        ));
    }

    private UUID parseTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
