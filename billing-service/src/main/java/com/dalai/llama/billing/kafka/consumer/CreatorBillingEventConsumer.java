package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.domain.event.CreatorAiUsageDebitEvent;
import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreatorBillingEventConsumer {

    private final UsageService usageService;

    @KafkaListener(
            topics = "${billing.kafka.creator-billing-events-topic:creator.billing.events}",
            groupId = "billing-service",
            containerFactory = "creatorAiUsageDebitListenerFactory"
    )
    public void consume(CreatorAiUsageDebitEvent event) {
        if (event.getEventId() == null) {
            log.warn("Skipping creator billing event without eventId");
            return;
        }
        if (event.getTenantId() == null) {
            log.warn("Skipping creator billing event {} without tenantId", event.getEventId());
            return;
        }

        long totalTokens = event.getTotalTokens();
        BigDecimal amount = defaultAmount(event.getAmount()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tokenRate = defaultAmount(event.getTokenRate());
        if (tokenRate.signum() <= 0 && totalTokens > 0 && amount.signum() > 0) {
            tokenRate = amount.divide(BigDecimal.valueOf(totalTokens), 8, RoundingMode.HALF_UP);
        }

        usageService.recordBillableUsage(new BillableUsageRequest(
                event.getTenantId(),
                UsageMetric.AI_LLM_TOKENS,
                BigDecimal.valueOf(Math.max(0, totalTokens)),
                BillingUnit.TOKEN,
                tokenRate,
                amount,
                "CREATOR_AI",
                resolveSourceId(event),
                description(event),
                null,
                event.getEventId().toString(),
                event.getCurrency(),
                instantValue(event)
        ));

        log.info("Processed creator AI usage event {} for tenant {}: tokens={}, amount={}",
                event.getEventId(), event.getTenantId(), totalTokens, amount);
    }

    private String description(CreatorAiUsageDebitEvent event) {
        String description = event.getDescription();
        if (description != null && !description.isBlank()) {
            return description;
        }
        String promptType = event.getPromptType();
        return promptType == null || promptType.isBlank()
                ? "Creator AI token usage"
                : "Creator AI " + promptType + " token usage";
    }

    private UUID resolveSourceId(CreatorAiUsageDebitEvent event) {
        if (event.getPromptRunId() != null) {
            return event.getPromptRunId();
        }
        if (event.getGenerationJobId() != null) {
            return event.getGenerationJobId();
        }
        return event.getEventId();
    }

    private Instant instantValue(CreatorAiUsageDebitEvent event) {
        return event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt().toInstant();
    }

    private BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
