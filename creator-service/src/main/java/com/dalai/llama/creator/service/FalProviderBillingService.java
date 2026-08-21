package com.dalai.llama.creator.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves fal.ai's provider-side charge before a customer margin is applied.
 * Per-request billing events are authoritative. Current endpoint pricing multiplied
 * by the completed output quantity is used only while a billing event is unavailable.
 */
@Service
public class FalProviderBillingService {

    private static final Logger log = LoggerFactory.getLogger(FalProviderBillingService.class);
    private static final String DEFAULT_PLATFORM_URL = "https://api.fal.ai";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public FalProviderBillingService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(firstText(
                        System.getProperty("fal.platform-api-base-url"),
                        System.getenv("FAL_PLATFORM_API_BASE_URL"),
                        DEFAULT_PLATFORM_URL
                ))
                .build();
    }

    public Map<String, Object> resolve(
            String provider,
            String model,
            Map<String, Object> sourceMetadata
    ) {
        Map<String, Object> result = new LinkedHashMap<>(sourceMetadata == null ? Map.of() : sourceMetadata);
        if (!isFal(provider, model, result)) {
            return result;
        }
        String apiKey = firstText(
                System.getProperty("fal.admin-key"),
                System.getenv("FAL_ADMIN_KEY"),
                System.getenv("FAL_KEY")
        );
        if (apiKey.isBlank()) {
            result.putIfAbsent("providerUsageResolution", "FAL_API_KEY_NOT_CONFIGURED_USING_PROVIDER_FALLBACK");
            return result;
        }

        List<String> requestIds = requestIds(result);
        if (!requestIds.isEmpty()) {
            Map<String, Object> billed = billingEvents(apiKey, requestIds);
            if (!billed.isEmpty()) {
                result.putAll(billed);
                clearPreviouslyCalculatedCustomerCost(result);
                return result;
            }
        }

        List<String> endpoints = endpointIds(model, result);
        if (endpoints.isEmpty()) {
            result.putIfAbsent("providerUsageResolution", "FAL_REQUEST_OR_ENDPOINT_ID_MISSING_USING_PROVIDER_FALLBACK");
            return result;
        }
        Map<String, Object> priced = currentPricing(apiKey, endpoints, result);
        if (!priced.isEmpty()) {
            result.putAll(priced);
            clearPreviouslyCalculatedCustomerCost(result);
        } else {
            result.putIfAbsent("providerUsageResolution", "FAL_BILLING_EVENT_PENDING_USING_PROVIDER_FALLBACK");
        }
        return result;
    }

    private void clearPreviouslyCalculatedCustomerCost(Map<String, Object> metadata) {
        metadata.remove("customerTotalCost");
        metadata.remove("billableTotalCost");
        metadata.remove("publishedAmount");
        metadata.remove("publishedAmountType");
    }

    private Map<String, Object> billingEvents(String apiKey, List<String> requestIds) {
        try {
            JsonNode response = webClient.get()
                    .uri(uri -> uri.path("/v1/models/billing-events")
                            .queryParam("request_id", String.join(",", requestIds))
                            .queryParam("limit", Math.max(10, requestIds.size() * 3))
                            .build())
                    .header("Authorization", "Key " + apiKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (response == null || !response.path("billing_events").isArray()) {
                return Map.of();
            }
            Set<String> expected = new LinkedHashSet<>(requestIds);
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal outputUnits = BigDecimal.ZERO;
            List<Map<String, Object>> lineItems = new ArrayList<>();
            for (JsonNode event : response.path("billing_events")) {
                String requestId = event.path("request_id").asText("");
                if (!expected.contains(requestId)) {
                    continue;
                }
                BigDecimal cost = decimal(event.get("cost_total"));
                if (cost.signum() <= 0) {
                    long nanos = event.path("cost_estimate_nano_usd").asLong(0);
                    cost = BigDecimal.valueOf(nanos, 9);
                }
                total = total.add(cost.max(BigDecimal.ZERO));
                outputUnits = outputUnits.add(decimal(event.get("output_units")).max(BigDecimal.ZERO));
                lineItems.add(Map.of(
                        "requestId", requestId,
                        "endpointId", event.path("endpoint_id").asText(""),
                        "outputUnits", decimal(event.get("output_units")),
                        "unitPrice", decimal(event.get("unit_price")),
                        "costSubtotal", decimal(event.get("cost_subtotal")),
                        "costDiscount", decimal(event.get("cost_discount")),
                        "costTotal", cost,
                        "currency", "USD"
                ));
            }
            if (lineItems.isEmpty() || total.signum() <= 0) {
                return Map.of();
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("currency", "USD");
            resolved.put("actualTotalCost", total.setScale(6, RoundingMode.HALF_UP));
            resolved.put("totalCost", total.setScale(6, RoundingMode.HALF_UP));
            resolved.put("providerReportedCost", total.setScale(6, RoundingMode.HALF_UP));
            resolved.put("providerReportedOutputUnits", outputUnits.setScale(4, RoundingMode.HALF_UP));
            resolved.put("providerBillingEvents", lineItems);
            resolved.put("estimated", false);
            resolved.put("pricingSource", "FAL_BILLING_EVENTS_API");
            resolved.put("providerUsageResolution", "PER_REQUEST_BILLING_EVENT");
            return resolved;
        } catch (RuntimeException ex) {
            logResolutionFailure("billing events", requestIds, ex);
            return Map.of();
        }
    }

    // fal.ai's /v1/models/billing-events and /v1/models/pricing require an Admin-scope
    // key - a regular inference key (what FAL_KEY normally holds) gets 403 Forbidden here
    // even though it works fine for actual generation calls. That 403 is expected and
    // already handled (falls back to local rate-table estimation below), so log it as one
    // clear line instead of a full stack trace on every single job - a full dump here was
    // pure noise, not something ops could act on differently from case to case. Set
    // FAL_ADMIN_KEY to an Admin-scope key from the fal.ai dashboard to get accurate
    // per-request provider billing instead of the estimated fallback.
    private void logResolutionFailure(String label, Object context, RuntimeException ex) {
        if (ex instanceof WebClientResponseException webEx && webEx.getStatusCode() == HttpStatus.FORBIDDEN) {
            log.warn("fal.ai {} request forbidden (context={}) - FAL_ADMIN_KEY is missing or not Admin-scope; "
                            + "falling back to local rate-table cost estimation for this job.",
                    label, context);
            return;
        }
        log.warn("Could not resolve fal.ai {} (context={}); using fallback", label, context, ex);
    }

    private Map<String, Object> currentPricing(
            String apiKey,
            List<String> endpoints,
            Map<String, Object> metadata
    ) {
        try {
            JsonNode response = webClient.get()
                    .uri(uri -> uri.path("/v1/models/pricing")
                            .queryParam("endpoint_id", String.join(",", endpoints))
                            .build())
                    .header("Authorization", "Key " + apiKey)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (response == null || !response.path("prices").isArray()) {
                return Map.of();
            }
            Map<String, Object> usage = mapValue(metadata.get("usage"));
            BigDecimal total = BigDecimal.ZERO;
            List<Map<String, Object>> lineItems = new ArrayList<>();
            for (JsonNode price : response.path("prices")) {
                String endpoint = price.path("endpoint_id").asText("");
                if (!endpoints.contains(endpoint)) {
                    continue;
                }
                String unit = price.path("unit").asText("output");
                BigDecimal unitPrice = decimal(price.get("unit_price"));
                BigDecimal quantity = quantityForUnit(unit, usage, endpoints.size());
                BigDecimal cost = unitPrice.multiply(quantity).setScale(6, RoundingMode.HALF_UP);
                total = total.add(cost);
                lineItems.add(Map.of(
                        "endpointId", endpoint,
                        "unit", unit,
                        "quantity", quantity,
                        "unitPrice", unitPrice,
                        "costTotal", cost,
                        "currency", price.path("currency").asText("USD")
                ));
            }
            if (lineItems.isEmpty() || total.signum() <= 0) {
                return Map.of();
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("currency", "USD");
            resolved.put("actualTotalCost", total.setScale(6, RoundingMode.HALF_UP));
            resolved.put("totalCost", total.setScale(6, RoundingMode.HALF_UP));
            resolved.put("falCurrentPricing", lineItems);
            resolved.put("estimated", true);
            resolved.put("pricingSource", "FAL_PRICING_API_WITH_COMPLETED_OUTPUT_USAGE");
            resolved.put("providerUsageResolution", "CURRENT_PRICE_TIMES_COMPLETED_OUTPUT");
            return resolved;
        } catch (RuntimeException ex) {
            logResolutionFailure("pricing", endpoints, ex);
            return Map.of();
        }
    }

    private BigDecimal quantityForUnit(String rawUnit, Map<String, Object> usage, int endpointCount) {
        String unit = rawUnit == null ? "" : rawUnit.toUpperCase(Locale.ROOT);
        if (unit.contains("MILLION") && (unit.contains("PIXEL") || unit.contains("SAMPLE"))) {
            return firstPositive(usage, "millionPixels", "estimatedMillionPixels", "millionSamples", "estimatedMillionSamples", "outputUnits");
        }
        if (unit.contains("SECOND")) {
            BigDecimal avatarSeconds = decimal(usage.get("avatarDurationSeconds"));
            BigDecimal duration = firstPositive(usage, "billableSeconds", "durationSeconds", "requestedDurationSeconds");
            if (endpointCount > 1 && avatarSeconds.signum() > 0) {
                return avatarSeconds;
            }
            return duration;
        }
        if (unit.contains("1000") && unit.contains("CHAR")) {
            return firstPositive(usage, "providerReportedCharacters", "billableCharacters", "characters")
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        }
        if (unit.contains("CHAR")) {
            return firstPositive(usage, "providerReportedCharacters", "billableCharacters", "characters");
        }
        return firstPositive(usage, "outputUnits", "clipCount", "videoCount", "requestCount");
    }

    private List<String> requestIds(Map<String, Object> metadata) {
        Map<String, Object> usage = mapValue(metadata.get("usage"));
        Set<String> values = new LinkedHashSet<>();
        addText(values, metadata.get("falRequestId"));
        addText(values, metadata.get("requestId"));
        addText(values, metadata.get("operationId"));
        addText(values, metadata.get("operationName"));
        addText(values, usage.get("falRequestId"));
        addText(values, usage.get("avatarRequestId"));
        addText(values, usage.get("lipSyncRequestId"));
        addList(values, metadata.get("falRequestIds"));
        addList(values, usage.get("falRequestIds"));
        return List.copyOf(values);
    }

    private List<String> endpointIds(String model, Map<String, Object> metadata) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : List.of(firstText(model), firstText(metadata.get("model")))) {
            for (String endpoint : raw.split("\\+")) {
                String normalized = endpoint.trim();
                if (!normalized.isBlank()
                        && !normalized.startsWith("fal-ai/")
                        && firstText(metadata.get("provider")).equalsIgnoreCase("seedance")) {
                    normalized = "fal-ai/" + normalized;
                }
                if (!normalized.isBlank() && (normalized.startsWith("fal-ai/") || normalized.contains("/"))) {
                    values.add(normalized);
                }
            }
        }
        return List.copyOf(values);
    }

    private boolean isFal(String provider, String model, Map<String, Object> metadata) {
        String normalized = firstText(provider, metadata.get("provider")).toLowerCase(Locale.ROOT);
        String endpoint = firstText(model, metadata.get("model")).toLowerCase(Locale.ROOT);
        return normalized.equals("fal.ai")
                || normalized.equals("fal_ai")
                || normalized.equals("fal")
                || normalized.equals("seedance")
                || endpoint.startsWith("fal-ai/");
    }

    private void addList(Set<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> addText(target, item));
        }
    }

    private void addText(Set<String> target, Object value) {
        String text = firstText(value);
        if (!text.isBlank()) {
            target.add(text);
        }
    }

    private BigDecimal firstPositive(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            BigDecimal value = decimal(values.get(key));
            if (value.signum() > 0) {
                return value;
            }
        }
        return BigDecimal.ONE;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof JsonNode node) {
            return node.isNumber() ? node.decimalValue() : decimal(node.asText(""));
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? BigDecimal.ZERO
                    : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}
