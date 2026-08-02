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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
    private final FalProviderBillingService falProviderBillingService;

    public CreatorAiService(
            List<CreatorAiProvider> creatorAiProviders,
            CreatorAiProviderCatalogService providerCatalogService,
            CreatorProperties properties,
            ObjectMapper objectMapper,
            KafkaTemplate<String, Object> kafkaTemplate,
            CreatorAiPricingService pricingService,
            BillingWalletService billingWalletService,
            FalProviderBillingService falProviderBillingService
    ) {
        this.aiProviders = indexProviders(creatorAiProviders);
        this.providerCatalogService = providerCatalogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.pricingService = pricingService;
        this.billingWalletService = billingWalletService;
        this.falProviderBillingService = falProviderBillingService;
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
        addCreatorPackageMetadata(eventCostMetadata, context);
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

        // The provider call has already incurred cost. Publishing must not depend on the
        // surrounding content transaction committing; an OOM/rollback must not erase usage.
        publish.run();
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
        Map<String, Object> resolvedCostMetadata = falProviderBillingService.resolve(provider, model, costMetadata);
        if (costMetadata != null && resolvedCostMetadata != costMetadata) {
            try {
                costMetadata.clear();
                costMetadata.putAll(resolvedCostMetadata);
            } catch (UnsupportedOperationException ignored) {
                // Some call sites intentionally pass immutable metadata; the resolved copy is still billed.
            }
        }
        costMetadata = resolvedCostMetadata;
        Boolean modelApiInteracted = booleanValue(costMetadata == null ? null : costMetadata.get("modelApiInteracted"));
        if (Boolean.FALSE.equals(modelApiInteracted)) {
            log.debug("Skipping provider billing debit for promptType={} provider={} because metadata is preflight-only",
                    promptType, provider);
            return;
        }
        BigDecimal actualAmount = firstBigDecimal(
                costMetadata == null ? null : costMetadata.get("actualTotalCost"),
                costMetadata == null ? null : costMetadata.get("totalCost"),
                costMetadata == null ? null : costMetadata.get("providerCost"),
                costMetadata == null ? null : costMetadata.get("legacyBillingCost")
        );
        BigDecimal markupPercent = billingMarkupPercent(costMetadata, promptType, provider);
        BigDecimal amount = firstBigDecimal(
                costMetadata == null ? null : costMetadata.get("customerTotalCost"),
                costMetadata == null ? null : costMetadata.get("billableTotalCost")
        );
        if (actualAmount != null && actualAmount.signum() > 0) {
            BigDecimal minimumAmount = applyUsageMarkup(actualAmount, markupPercent, 6);
            if (amount == null || amount.signum() <= 0 || amount.compareTo(minimumAmount) < 0) {
                amount = minimumAmount;
            }
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
        addCreatorPackageMetadata(normalizedCostMetadata, context);
        UUID eventId = providerUsageEventId(promptType, provider, model, costMetadata, context);
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
        normalizedCostMetadata.put("billingMarkupPercent", markupPercent);
        normalizedCostMetadata.put("billingMarkupMultiplier", usageMarkupMultiplier(markupPercent));
        normalizedCostMetadata.put("billingMarkupAppliedBy", isVideoProviderUsage(promptType, provider)
                ? "creator-service:video"
                : "creator-service:general");
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
        long providerUsageTokens = providerUsageTokens(normalizedCostMetadata);
        if (providerUsageTokens > 0) {
            tokenMetadata.put("providerReportedTokens", providerUsageTokens);
            tokenMetadata.put("actualTotalTokens", providerUsageTokens);
            tokenMetadata.put("billableTotalTokens", markedUpTokens(providerUsageTokens));
            tokenMetadata.put("source", "PROVIDER_USAGE_METADATA");
        }

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
                .totalTokens(providerUsageTokens)
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

        log.info(
                "WALLET_DEBIT_AUDIT_REQUEST eventId={} tenantId={} promptType={} provider={} model={} amount={} currency={} markupPercent={} sourceId={} description={}",
                eventId,
                tenantId,
                promptType,
                provider,
                model,
                amount,
                currency,
                markupPercent,
                context.promptRunId() == null ? context.generationJobId() : context.promptRunId(),
                description
        );

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

        // External provider usage is billable once the provider completed, even when the
        // later job-state transaction fails. Billing is idempotent by this stable event id.
        publish.run();
    }

    private UUID providerUsageEventId(
            String promptType,
            String provider,
            String model,
            Map<String, Object> costMetadata,
            AiUsageContext context
    ) {
        UUID supplied = parseUuid(stringValue(costMetadata == null ? null : costMetadata.get("eventId")));
        if (supplied != null) {
            return supplied;
        }
        String sourceId = context == null
                ? ""
                : context.promptRunId() != null
                ? context.promptRunId().toString()
                : context.generationJobId() == null ? "" : context.generationJobId().toString();
        if (sourceId.isBlank()) {
            return UUID.randomUUID();
        }
        Map<String, Object> usage = mapValue(costMetadata == null ? null : costMetadata.get("usage"));
        String usageScope = stringValue(usage.get("sceneId"));
        if (usageScope.isBlank()) usageScope = stringValue(usage.get("sceneNumber"));
        if (usageScope.isBlank()) usageScope = stringValue(usage.get("assetId"));
        if (usageScope.isBlank()) {
            usageScope = stringValue(costMetadata == null ? null : costMetadata.get("operationId"));
        }
        if (usageScope.isBlank()) usageScope = "default";
        String seed = String.join("|",
                "creator-provider-debit-v1",
                sourceId,
                stringValue(promptType),
                stringValue(provider),
                stringValue(model),
                usageScope
        );
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private void addCreatorPackageMetadata(Map<String, Object> costMetadata, AiUsageContext context) {
        if (costMetadata == null || context == null || context.projectId() == null) {
            return;
        }
        costMetadata.putIfAbsent("packageCode", "AI_SHORT_STARTER_60");
        costMetadata.putIfAbsent("packageScopeId", context.projectId().toString());
        costMetadata.putIfAbsent("packagePriceInr", new BigDecimal("5999"));
        costMetadata.putIfAbsent("includedInPackage", true);
        costMetadata.putIfAbsent("packagePolicy", "END_TO_END_60_SECOND_VIDEO_WITH_TWO_CLIENT_REVIEWS");
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
        return applyUsageMarkup(amount, usageMarkupPercent(), scale);
    }

    private BigDecimal applyUsageMarkup(BigDecimal amount, BigDecimal markupPercent, int scale) {
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
        return safeAmount.multiply(usageMarkupMultiplier(markupPercent)).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal usageMarkupMultiplier() {
        return usageMarkupMultiplier(usageMarkupPercent());
    }

    private BigDecimal usageMarkupMultiplier(BigDecimal markupPercent) {
        BigDecimal safePercent = markupPercent == null ? BigDecimal.ZERO : markupPercent.max(BigDecimal.ZERO);
        return BigDecimal.ONE.add(safePercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
    }

    private BigDecimal usageMarkupPercent() {
        BigDecimal percent = properties.getAi().getBilling().getUsageMarkupPercent();
        return percent == null ? BigDecimal.ZERO : percent.max(BigDecimal.ZERO);
    }

    private BigDecimal videoUsageMarkupPercent() {
        BigDecimal percent = properties.getAi().getBilling().getVideoUsageMarkupPercent();
        return percent == null ? BigDecimal.valueOf(20) : percent.max(BigDecimal.ZERO);
    }

    private BigDecimal billingMarkupPercent(Map<String, Object> costMetadata, String promptType, String provider) {
        BigDecimal provided = bigDecimalValue(costMetadata == null ? null : costMetadata.get("billingMarkupPercent"));
        if (provided != null && provided.signum() >= 0) {
            return provided;
        }
        return isVideoProviderUsage(promptType, provider) ? videoUsageMarkupPercent() : usageMarkupPercent();
    }

    private boolean isVideoProviderUsage(String promptType, String provider) {
        String normalizedPromptType = stringValue(promptType).trim().toUpperCase(Locale.ROOT);
        String normalizedProvider = stringValue(provider).trim().toLowerCase(Locale.ROOT);
        return normalizedPromptType.contains("VIDEO")
                || normalizedProvider.equals("google_veo")
                || normalizedProvider.equals("gemini_omni")
                || normalizedProvider.equals("google_omni")
                || normalizedProvider.equals("seedance")
                || normalizedProvider.equals("luma")
                || normalizedProvider.equals("runway")
                || normalizedProvider.equals("decart");
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

    private long providerUsageTokens(Map<String, Object> costMetadata) {
        if (costMetadata == null || costMetadata.isEmpty()) {
            return 0;
        }
        Map<String, Object> usage = mapValue(costMetadata.get("usage"));
        Map<String, Object> providerUsage = mapValue(usage.get("providerUsage"));
        return positiveOrFallback(
                firstLong(usage, "billableTokens", "billable_tokens", "videoTokens", "video_tokens", "reportedTokens", "totalTokens", "total_tokens"),
                firstLong(providerUsage, "billableTokens", "billable_tokens", "videoTokens", "video_tokens", "totalTokens", "total_tokens")
        );
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
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

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return null;
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
