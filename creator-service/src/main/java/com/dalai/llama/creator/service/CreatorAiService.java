package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.CreatorAiProvider;
import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.event.CreatorAiUsageDebitEvent;
import com.dalai.llama.creator.service.BillingWalletService.WalletBalanceCheckException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorAiService {

    private static final Logger log = LoggerFactory.getLogger(CreatorAiService.class);
    private final Map<String, CreatorAiProvider> aiProviders;
    private final CreatorAiProviderCatalogService providerCatalogService;
    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CreatorAiPricingService pricingService;
    private final BillingWalletService billingWalletService;

    public CreatorAiService(
            List<CreatorAiProvider> creatorAiProviders,
            CreatorAiProviderCatalogService providerCatalogService,
            CreatorProperties properties,
            ObjectMapper objectMapper,
            KafkaTemplate<String, Object> kafkaTemplate,
            CreatorAiPricingService pricingService,
            BillingWalletService billingWalletService
    ) {
        this.aiProviders = indexProviders(creatorAiProviders);
        this.providerCatalogService = providerCatalogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.pricingService = pricingService;
        this.billingWalletService = billingWalletService;
    }

    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        return currentProvider().generate(promptType, input);
    }

    public MeteredAiResponse generateMetered(String promptType, Map<String, Object> input, AiUsageContext context) {
        assertWalletBalanceForModelRun(promptType, context);
        Map<String, Object> output = currentProvider().generate(promptType, input);
        TokenUsage tokenUsage = resolveTokenUsage(input, output);
        CostUsage costUsage = calculateCost(tokenUsage);
        Map<String, Object> providerCost = pricingService.estimateTextCall(
                providerName(),
                modelName(),
                promptType,
                tokenUsage.inputTokens(),
                tokenUsage.outputTokens(),
                tokenUsage.source()
        );
        long billableInputTokens = markedUpTokens(tokenUsage.inputTokens());
        long billableOutputTokens = markedUpTokens(tokenUsage.outputTokens());
        long billableTotalTokens = markedUpTokens(tokenUsage.totalTokens());
        BigDecimal billableTotalCost = applyUsageMarkup(costUsage.totalCost(), 4);
        UUID eventId = UUID.randomUUID();

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", tokenUsage.inputTokens());
        tokenMetadata.put("outputTokens", tokenUsage.outputTokens());
        tokenMetadata.put("totalTokens", tokenUsage.totalTokens());
        tokenMetadata.put("actualInputTokens", tokenUsage.inputTokens());
        tokenMetadata.put("actualOutputTokens", tokenUsage.outputTokens());
        tokenMetadata.put("actualTotalTokens", tokenUsage.totalTokens());
        tokenMetadata.put("billableInputTokens", billableInputTokens);
        tokenMetadata.put("billableOutputTokens", billableOutputTokens);
        tokenMetadata.put("billableTotalTokens", billableTotalTokens);
        tokenMetadata.put("billingMarkupPercent", usageMarkupPercent());
        tokenMetadata.put("billingMarkupMultiplier", usageMarkupMultiplier());
        tokenMetadata.put("source", tokenUsage.source());
        tokenMetadata.put("provider", providerName());
        tokenMetadata.put("model", modelName());
        tokenMetadata.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        tokenMetadata.put("finishReason", stringValue(output == null ? null : output.get("finishReason")));
        tokenMetadata.put("finishReasons", output == null ? List.of() : stringList(output.get("finishReasons")));

        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("eventId", eventId.toString());
        costMetadata.put("billingEnabled", properties.getAi().getBilling().isEnabled());
        costMetadata.put("currency", properties.getAi().getBilling().getCurrency());
        costMetadata.put("tokenRate", costUsage.tokenRate());
        costMetadata.put("rateUnit", "TOKEN");
        costMetadata.put("totalCost", costUsage.totalCost());
        costMetadata.put("actualTotalCost", costUsage.totalCost());
        costMetadata.put("billableTotalCost", billableTotalCost);
        costMetadata.put("customerTotalCost", billableTotalCost);
        costMetadata.put("billingMarkupPercent", usageMarkupPercent());
        costMetadata.put("billingMarkupMultiplier", usageMarkupMultiplier());
        costMetadata.put("billingMarkupAppliedBy", "creator-service");
        costMetadata.put("legacyBillingCost", costUsage.totalCost());
        costMetadata.put("legacyBillingCurrency", properties.getAi().getBilling().getCurrency());
        costMetadata.put("providerCost", providerCost.get("totalCost"));
        costMetadata.put("providerCurrency", providerCost.get("currency"));
        costMetadata.put("providerPricing", providerCost);
        costMetadata.put("provider", providerName());
        costMetadata.put("model", modelName());
        costMetadata.put("promptType", promptType);
        costMetadata.put("generatedAt", OffsetDateTime.now().toString());
        if (context != null && context.generationJobId() != null) {
            costMetadata.put("generationJobId", context.generationJobId().toString());
        }

        return new MeteredAiResponse(output, tokenMetadata, costMetadata, eventId, costUsage.totalCost());
    }

    public void assertWalletBalanceForModelRun(String promptType, AiUsageContext context) {
        if (!properties.getBilling().isWalletGuardEnabled()) {
            return;
        }
        BigDecimal minimumBalance = properties.getBilling().getMinimumWalletBalance();
        if (minimumBalance == null || minimumBalance.signum() <= 0) {
            return;
        }
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return;
        }
        UUID tenantId = parseUuid(context.tenantId());
        if (tenantId == null) {
            return;
        }

        try {
            BigDecimal currentBalance = billingWalletService.getWalletBalance(tenantId);
            if (currentBalance.compareTo(minimumBalance) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "Insufficient balance. Minimum wallet balance is " + minimumBalance + " before running paid AI generation."
                );
            }
        } catch (WalletBalanceCheckException ex) {
            log.warn("Creator AI wallet balance check failed promptType={} tenantId={}", promptType, tenantId, ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to verify wallet balance.", ex);
        }
    }

    public void publishBillingDebit(String promptType, MeteredAiResponse response, AiUsageContext context) {
        if (response == null || !properties.getAi().getBilling().isEnabled()) {
            return;
        }
        if (response.totalCost() == null || response.totalCost().signum() <= 0) {
            return;
        }
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            log.warn("Skipping creator AI billing event because tenantId is missing for promptType={}", promptType);
            return;
        }
        BigDecimal actualAmount = firstBigDecimal(
                response.costMetadata().get("actualTotalCost"),
                response.costMetadata().get("totalCost"),
                response.costMetadata().get("legacyBillingCost"),
                response.costMetadata().get("providerCost"),
                response.totalCost()
        );
        BigDecimal debitAmount = firstBigDecimal(
                response.costMetadata().get("customerTotalCost"),
                response.costMetadata().get("billableTotalCost")
        );
        if ((debitAmount == null || debitAmount.signum() <= 0) && actualAmount != null && actualAmount.signum() > 0) {
            debitAmount = applyUsageMarkup(actualAmount, 4);
        }
        if (debitAmount == null || debitAmount.signum() <= 0) {
            return;
        }
        UUID tenantId = parseUuid(context.tenantId());
        if (tenantId == null) {
            log.warn("Skipping creator AI billing event because tenantId is not a UUID for promptType={}", promptType);
            return;
        }

        Map<String, Object> eventCostMetadata = new LinkedHashMap<>(response.costMetadata());
        if (actualAmount != null && actualAmount.signum() > 0) {
            eventCostMetadata.putIfAbsent("actualTotalCost", actualAmount);
        }
        eventCostMetadata.put("billableTotalCost", debitAmount);
        eventCostMetadata.put("customerTotalCost", debitAmount);
        eventCostMetadata.put("billingMarkupPercent", usageMarkupPercent());
        eventCostMetadata.put("billingMarkupMultiplier", usageMarkupMultiplier());
        eventCostMetadata.put("billingMarkupAppliedBy", "creator-service");
        eventCostMetadata.put("publishedAmount", debitAmount);
        eventCostMetadata.put("publishedAmountType", "CUSTOMER_COST_WITH_CREATOR_MARGIN");
        long eventInputTokens = positiveOrFallback(
                longValue(response.tokenMetadata().get("billableInputTokens")),
                longValue(response.tokenMetadata().get("inputTokens"))
        );
        long eventOutputTokens = positiveOrFallback(
                longValue(response.tokenMetadata().get("billableOutputTokens")),
                longValue(response.tokenMetadata().get("outputTokens"))
        );
        long eventTotalTokens = positiveOrFallback(
                longValue(response.tokenMetadata().get("billableTotalTokens")),
                eventInputTokens + eventOutputTokens,
                longValue(response.tokenMetadata().get("totalTokens"))
        );

        CreatorAiUsageDebitEvent event = CreatorAiUsageDebitEvent.builder()
                .eventId(response.eventId())
                .tenantId(tenantId)
                .userId(context.userId())
                .projectId(context.projectId())
                .generationJobId(context.generationJobId())
                .promptRunId(context.promptRunId())
                .promptType(promptType)
                .provider(providerName())
                .model(modelName())
                .inputTokens(eventInputTokens)
                .outputTokens(eventOutputTokens)
                .totalTokens(eventTotalTokens)
                .tokenRate((BigDecimal) response.costMetadata().get("tokenRate"))
                .rateUnit("TOKEN")
                .amount(debitAmount)
                .currency(properties.getAi().getBilling().getCurrency())
                .description("Creator AI " + promptType + " token usage")
                .occurredAt(OffsetDateTime.now())
                .tokenMetadata(response.tokenMetadata())
                .costMetadata(eventCostMetadata)
                .build();

        Runnable publish = () -> kafkaTemplate
                .send(properties.getKafka().getBillingEventsTopic(), tenantId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish creator AI billing event {}", response.eventId(), ex);
                    } else {
                        log.debug("Published creator AI billing event {} to {}",
                                response.eventId(), properties.getKafka().getBillingEventsTopic());
                    }
                });

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    public void publishProviderUsageDebit(
            String promptType,
            String provider,
            String model,
            Map<String, Object> costMetadata,
            AiUsageContext context,
            String description
    ) {
        if (!properties.getAi().getBilling().isEnabled()) {
            return;
        }
        BigDecimal actualAmount = firstBigDecimal(
                costMetadata == null ? null : costMetadata.get("actualTotalCost"),
                costMetadata == null ? null : costMetadata.get("totalCost"),
                costMetadata == null ? null : costMetadata.get("providerCost"),
                costMetadata == null ? null : costMetadata.get("legacyBillingCost")
        );
        BigDecimal amount = firstBigDecimal(
                costMetadata == null ? null : costMetadata.get("customerTotalCost"),
                costMetadata == null ? null : costMetadata.get("billableTotalCost")
        );
        if ((amount == null || amount.signum() <= 0) && actualAmount != null && actualAmount.signum() > 0) {
            amount = applyUsageMarkup(actualAmount, 6);
        }
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            log.warn("Skipping creator provider billing event because tenantId is missing for promptType={}", promptType);
            return;
        }
        UUID tenantId = parseUuid(context.tenantId());
        if (tenantId == null) {
            log.warn("Skipping creator provider billing event because tenantId is not a UUID for promptType={}", promptType);
            return;
        }

        Map<String, Object> normalizedCostMetadata = new LinkedHashMap<>(costMetadata == null ? Map.of() : costMetadata);
        UUID eventId = UUID.randomUUID();
        normalizedCostMetadata.putIfAbsent("eventId", eventId.toString());
        normalizedCostMetadata.putIfAbsent("billingEnabled", properties.getAi().getBilling().isEnabled());
        normalizedCostMetadata.putIfAbsent("provider", provider);
        normalizedCostMetadata.putIfAbsent("model", model);
        normalizedCostMetadata.putIfAbsent("promptType", promptType);
        normalizedCostMetadata.putIfAbsent("generatedAt", OffsetDateTime.now().toString());
        if (actualAmount != null && actualAmount.signum() > 0) {
            normalizedCostMetadata.putIfAbsent("actualTotalCost", actualAmount);
        }
        normalizedCostMetadata.put("billableTotalCost", amount);
        normalizedCostMetadata.put("customerTotalCost", amount);
        normalizedCostMetadata.put("billingMarkupPercent", usageMarkupPercent());
        normalizedCostMetadata.put("billingMarkupMultiplier", usageMarkupMultiplier());
        normalizedCostMetadata.put("billingMarkupAppliedBy", "creator-service");
        normalizedCostMetadata.put("publishedAmount", amount);
        normalizedCostMetadata.put("publishedAmountType", "CUSTOMER_COST_WITH_CREATOR_MARGIN");
        if (context.generationJobId() != null) {
            normalizedCostMetadata.putIfAbsent("generationJobId", context.generationJobId().toString());
        }
        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NON_TOKEN_PROVIDER_USAGE");
        tokenMetadata.put("provider", provider);
        tokenMetadata.put("model", model);
        tokenMetadata.put("usage", normalizedCostMetadata.get("usage"));

        String currency = stringValue(normalizedCostMetadata.get("currency"));
        if (currency.isBlank()) {
            currency = properties.getAi().getBilling().getCurrency();
        }
        String rateUnit = stringValue(normalizedCostMetadata.get("rateUnit"));
        if (rateUnit.isBlank()) {
            rateUnit = "PROVIDER_USAGE";
        }
        BigDecimal rate = firstBigDecimal(
                normalizedCostMetadata.get("ratePerSecond"),
                normalizedCostMetadata.get("ratePerMinute"),
                normalizedCostMetadata.get("ratePerMillionPixels"),
                normalizedCostMetadata.get("creditUsd"),
                normalizedCostMetadata.get("ratePerCredit"),
                normalizedCostMetadata.get("ratePerClip"),
                normalizedCostMetadata.get("rate"),
                BigDecimal.ZERO
        );

        CreatorAiUsageDebitEvent event = CreatorAiUsageDebitEvent.builder()
                .eventId(eventId)
                .tenantId(tenantId)
                .userId(context.userId())
                .projectId(context.projectId())
                .generationJobId(context.generationJobId())
                .promptRunId(context.promptRunId())
                .promptType(promptType)
                .provider(provider)
                .model(model)
                .inputTokens(0)
                .outputTokens(0)
                .totalTokens(0)
                .tokenRate(rate)
                .rateUnit(rateUnit)
                .amount(amount)
                .currency(currency)
                .description(description == null || description.isBlank()
                        ? "Creator AI " + promptType + " provider usage"
                        : description)
                .occurredAt(OffsetDateTime.now())
                .tokenMetadata(tokenMetadata)
                .costMetadata(normalizedCostMetadata)
                .build();

        Runnable publish = () -> kafkaTemplate
                .send(properties.getKafka().getBillingEventsTopic(), tenantId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish creator provider billing event {}", eventId, ex);
                    } else {
                        log.debug("Published creator provider billing event {} to {}",
                                eventId, properties.getKafka().getBillingEventsTopic());
                    }
                });

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    public String providerName() {
        return currentProvider().providerName();
    }

    public String modelName() {
        if ("gemini".equalsIgnoreCase(providerName())) {
            return properties.getAi().getGeminiModel();
        }
        return properties.getAi().getModel();
    }

    private CreatorAiProvider currentProvider() {
        String providerCode = providerCatalogService.resolveProviderCode();
        CreatorAiProvider provider = aiProviders.get(providerCode);
        if (provider != null) {
            return provider;
        }
        throw new IllegalStateException("No creator AI provider bean found for provider code: " + providerCode);
    }

    private Map<String, CreatorAiProvider> indexProviders(List<CreatorAiProvider> providers) {
        Map<String, CreatorAiProvider> indexed = new HashMap<>();
        for (CreatorAiProvider provider : providers) {
            indexed.put(provider.providerName().toLowerCase(Locale.ROOT), provider);
        }
        return indexed;
    }

    private TokenUsage resolveTokenUsage(Map<String, Object> input, Map<String, Object> output) {
        Map<String, Object> providerUsage = extractProviderTokenUsage(output);
        if (!providerUsage.isEmpty()) {
            long inputTokens = firstLong(providerUsage, "inputTokens", "promptTokens", "prompt_tokens");
            long outputTokens = firstLong(providerUsage, "outputTokens", "completionTokens", "completion_tokens");
            long totalTokens = firstLong(providerUsage, "totalTokens", "total_tokens");
            if (totalTokens <= 0) {
                totalTokens = Math.max(0, inputTokens) + Math.max(0, outputTokens);
            }
            if (inputTokens <= 0 && outputTokens <= 0 && totalTokens > 0) {
                inputTokens = totalTokens;
            }
            return new TokenUsage(Math.max(0, inputTokens), Math.max(0, outputTokens), Math.max(0, totalTokens), "PROVIDER");
        }

        long inputTokens = estimateTokens(input);
        long outputTokens = estimateTokens(output);
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, "ESTIMATED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractProviderTokenUsage(Map<String, Object> output) {
        if (output == null) {
            return Map.of();
        }
        Object tokenUsage = output.get("tokenUsage");
        if (tokenUsage == null) {
            tokenUsage = output.get("usage");
        }
        if (tokenUsage instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private CostUsage calculateCost(TokenUsage tokenUsage) {
        BigDecimal tokenRate = properties.getAi().getBilling().getTokenRate() == null
                ? BigDecimal.ZERO
                : properties.getAi().getBilling().getTokenRate();
        BigDecimal totalCost = BigDecimal.valueOf(tokenUsage.totalTokens())
                .multiply(tokenRate)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal minimumCharge = properties.getAi().getBilling().getMinimumCharge();
        if (tokenUsage.totalTokens() > 0 && minimumCharge != null && minimumCharge.signum() > 0
                && totalCost.compareTo(minimumCharge) < 0) {
            totalCost = minimumCharge.setScale(4, RoundingMode.HALF_UP);
        }
        return new CostUsage(tokenRate, totalCost);
    }

    private long markedUpTokens(long tokens) {
        if (tokens <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(usageMarkupMultiplier())
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    private BigDecimal applyUsageMarkup(BigDecimal amount, int scale) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        return safeAmount.multiply(usageMarkupMultiplier()).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal usageMarkupMultiplier() {
        return BigDecimal.ONE.add(usageMarkupPercent().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal usageMarkupPercent() {
        BigDecimal percent = properties.getAi().getBilling().getUsageMarkupPercent();
        return percent == null ? BigDecimal.ZERO : percent.max(BigDecimal.ZERO);
    }

    private long estimateTokens(Object value) {
        String json = toJson(value);
        if (json == null || json.isBlank()) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(json.length() / 4.0d));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private long firstLong(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            long value = longValue(map.get(key));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private long positiveOrFallback(long... values) {
        if (values != null) {
            for (long value : values) {
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0;
    }

    private BigDecimal firstBigDecimal(Object... values) {
        for (Object value : values) {
            BigDecimal decimal = bigDecimalValue(value);
            if (decimal != null) {
                return decimal;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal bigDecimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::stringValue)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = stringValue(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record AiUsageContext(
            String tenantId,
            String userId,
            UUID projectId,
            UUID generationJobId,
            UUID promptRunId
    ) {
        public AiUsageContext withPromptRunId(UUID promptRunId) {
            return new AiUsageContext(tenantId, userId, projectId, generationJobId, promptRunId);
        }
    }

    public record MeteredAiResponse(
            Map<String, Object> output,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            UUID eventId,
            BigDecimal totalCost
    ) {
    }

    private record TokenUsage(long inputTokens, long outputTokens, long totalTokens, String source) {
    }

    private record CostUsage(BigDecimal tokenRate, BigDecimal totalCost) {
    }
}
