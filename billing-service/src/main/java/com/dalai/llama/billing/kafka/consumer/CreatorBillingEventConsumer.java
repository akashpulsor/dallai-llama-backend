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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
        UsageMetric metric = resolveMetric(event);
        BillingUnit unit = resolveUnit(event, metric);
        BigDecimal quantity = resolveQuantity(event, metric, totalTokens);
        if (quantity.signum() > 0 && amount.signum() > 0) {
            tokenRate = amount.divide(quantity, 8, RoundingMode.HALF_UP);
        }

        usageService.recordBillableUsage(new BillableUsageRequest(
                event.getTenantId(),
                metric,
                quantity,
                unit,
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

    private UsageMetric resolveMetric(CreatorAiUsageDebitEvent event) {
        String rateUnit = normalize(event.getRateUnit());
        String provider = normalize(event.getProvider());
        String promptType = normalize(event.getPromptType());
        if (rateUnit.contains("MILLION_PIXELS")) {
            return UsageMetric.AI_VIDEO_MILLION_PIXELS;
        }
        if (rateUnit.equals("CREDIT") || rateUnit.contains("CREDITS")) {
            return UsageMetric.AI_VIDEO_CREDITS;
        }
        if (rateUnit.equals("CLIP") || rateUnit.contains("CLIP")) {
            return UsageMetric.AI_MUSIC_CLIPS;
        }
        if (promptType.contains("AUDIO") || provider.contains("AUDIO")) {
            return UsageMetric.AI_AUDIO_SECONDS;
        }
        if (rateUnit.equals("SECOND") || rateUnit.contains("SECOND")) {
            return UsageMetric.AI_VIDEO_SECONDS;
        }
        if (event.getTotalTokens() > 0) {
            return UsageMetric.AI_LLM_TOKENS;
        }
        return UsageMetric.AI_PROVIDER_USAGE;
    }

    private BillingUnit resolveUnit(CreatorAiUsageDebitEvent event, UsageMetric metric) {
        return switch (metric) {
            case AI_VIDEO_MILLION_PIXELS -> BillingUnit.MILLION_PIXELS;
            case AI_VIDEO_CREDITS -> BillingUnit.CREDIT;
            case AI_MUSIC_CLIPS -> BillingUnit.CLIP;
            case AI_VIDEO_SECONDS, AI_AUDIO_SECONDS -> BillingUnit.SECOND;
            case AI_LLM_TOKENS -> BillingUnit.TOKEN;
            default -> BillingUnit.COUNT;
        };
    }

    private BigDecimal resolveQuantity(CreatorAiUsageDebitEvent event, UsageMetric metric, long totalTokens) {
        Map<String, Object> costMetadata = event.getCostMetadata();
        Map<String, Object> usage = mapValue(costMetadata == null ? null : costMetadata.get("usage"));
        return switch (metric) {
            case AI_VIDEO_MILLION_PIXELS -> firstPositive(
                    decimalValue(usage.get("estimatedMillionPixels")),
                    decimalValue(usage.get("millionPixels")),
                    decimalValue(costMetadata == null ? null : costMetadata.get("millionPixels")),
                    BigDecimal.ONE
            );
            case AI_VIDEO_CREDITS -> firstPositive(
                    decimalValue(usage.get("credits")),
                    decimalValue(costMetadata == null ? null : costMetadata.get("totalCredits")),
                    BigDecimal.ONE
            );
            case AI_MUSIC_CLIPS -> firstPositive(
                    decimalValue(usage.get("billableClipUnits")),
                    decimalValue(usage.get("customerBillableClipUnits")),
                    BigDecimal.ONE
            );
            case AI_VIDEO_SECONDS, AI_AUDIO_SECONDS -> firstPositive(
                    decimalValue(usage.get("durationSeconds")),
                    decimalValue(usage.get("requestedDurationSeconds")),
                    decimalValue(usage.get("billableSeconds")),
                    BigDecimal.ONE
            );
            case AI_LLM_TOKENS -> BigDecimal.valueOf(Math.max(0, totalTokens));
            default -> BigDecimal.ONE;
        };
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        if (values != null) {
            for (BigDecimal value : values) {
                if (value != null && value.signum() > 0) {
                    return value.setScale(4, RoundingMode.HALF_UP);
                }
            }
        }
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
