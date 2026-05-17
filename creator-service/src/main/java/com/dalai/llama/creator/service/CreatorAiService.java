package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.CreatorAiProvider;
import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.event.CreatorAiUsageDebitEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public CreatorAiService(
            List<CreatorAiProvider> creatorAiProviders,
            CreatorAiProviderCatalogService providerCatalogService,
            CreatorProperties properties,
            ObjectMapper objectMapper,
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.aiProviders = indexProviders(creatorAiProviders);
        this.providerCatalogService = providerCatalogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        return currentProvider().generate(promptType, input);
    }

    public MeteredAiResponse generateMetered(String promptType, Map<String, Object> input, AiUsageContext context) {
        Map<String, Object> output = currentProvider().generate(promptType, input);
        TokenUsage tokenUsage = resolveTokenUsage(input, output);
        CostUsage costUsage = calculateCost(tokenUsage);
        UUID eventId = UUID.randomUUID();

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", tokenUsage.inputTokens());
        tokenMetadata.put("outputTokens", tokenUsage.outputTokens());
        tokenMetadata.put("totalTokens", tokenUsage.totalTokens());
        tokenMetadata.put("source", tokenUsage.source());

        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("eventId", eventId.toString());
        costMetadata.put("billingEnabled", properties.getAi().getBilling().isEnabled());
        costMetadata.put("currency", properties.getAi().getBilling().getCurrency());
        costMetadata.put("tokenRate", costUsage.tokenRate());
        costMetadata.put("rateUnit", "TOKEN");
        costMetadata.put("totalCost", costUsage.totalCost());
        costMetadata.put("provider", providerName());
        costMetadata.put("model", modelName());
        costMetadata.put("promptType", promptType);
        costMetadata.put("generatedAt", OffsetDateTime.now().toString());
        if (context != null && context.generationJobId() != null) {
            costMetadata.put("generationJobId", context.generationJobId().toString());
        }

        return new MeteredAiResponse(output, tokenMetadata, costMetadata, eventId, costUsage.totalCost());
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
        UUID tenantId = parseUuid(context.tenantId());
        if (tenantId == null) {
            log.warn("Skipping creator AI billing event because tenantId is not a UUID for promptType={}", promptType);
            return;
        }

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
                .inputTokens(longValue(response.tokenMetadata().get("inputTokens")))
                .outputTokens(longValue(response.tokenMetadata().get("outputTokens")))
                .totalTokens(longValue(response.tokenMetadata().get("totalTokens")))
                .tokenRate((BigDecimal) response.costMetadata().get("tokenRate"))
                .rateUnit("TOKEN")
                .amount(response.totalCost())
                .currency(properties.getAi().getBilling().getCurrency())
                .description("Creator AI " + promptType + " token usage")
                .occurredAt(OffsetDateTime.now())
                .tokenMetadata(response.tokenMetadata())
                .costMetadata(response.costMetadata())
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

    public String providerName() {
        return currentProvider().providerName();
    }

    public String modelName() {
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
