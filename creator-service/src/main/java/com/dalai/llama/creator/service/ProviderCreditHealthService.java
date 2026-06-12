package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ProviderCreditHealthService {

    private final CreatorProperties properties;
    private final BillingWalletService billingWalletService;
    private final WebClient webClient;

    public ProviderCreditHealthService(
            CreatorProperties properties,
            BillingWalletService billingWalletService,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.billingWalletService = billingWalletService;
        this.webClient = webClientBuilder.build();
    }

    public Map<String, Object> creditHealth() {
        return creditHealth(null);
    }

    public Map<String, Object> creditHealth(String tenantId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checkedAt", Instant.now().toString());
        response.put("creditCheckEnabled", properties.getAi().isProviderCreditCheckEnabled());
        response.put("wallet", walletHealth(tenantId));
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("luma", checkProvider("luma").toMap());
        providers.put("runway", checkProvider("runway").toMap());
        providers.put("decart", checkProvider("decart").toMap());
        providers.put("google_veo", googleVeoStatus().toMap());
        response.put("providers", providers);
        return response;
    }

    public ProviderCreditStatus checkProvider(String provider) {
        return switch (normalizeProvider(provider)) {
            case "luma" -> checkLuma();
            case "runway" -> checkRunway();
            case "decart" -> decartStatus();
            case "google_veo" -> googleVeoStatus();
            default -> new ProviderCreditStatus(
                    provider,
                    "Custom Provider",
                    false,
                    true,
                    "UNSUPPORTED",
                    properties.getAi().isProviderCreditCheckEnabled(),
                    "Unsupported video provider for Studio Polish.",
                    Map.of()
            );
        };
    }

    public void requirePolishProviderReady(String provider) {
        if (!properties.getAi().isProviderCreditCheckEnabled()) {
            return;
        }
        String normalized = normalizeProvider(provider);
        if (!normalized.equals("luma") && !normalized.equals("runway") && !normalized.equals("decart")) {
            return;
        }
        ProviderCreditStatus status = checkProvider(normalized);
        if (status.polishDisabled()) {
            throw new IllegalStateException(status.message());
        }
    }

    private Map<String, Object> walletHealth(String tenantId) {
        Map<String, Object> wallet = new LinkedHashMap<>();
        BigDecimal minimumBalance = positiveDecimal(properties.getBilling().getMinimumWalletBalance(), BigDecimal.ZERO);
        wallet.put("walletGuardEnabled", properties.getBilling().isWalletGuardEnabled());
        wallet.put("minimumBalance", minimumBalance);
        wallet.put("currency", properties.getAi().getBilling().getCurrency());

        if (!properties.getBilling().isWalletGuardEnabled()) {
            wallet.put("checked", false);
            wallet.put("available", true);
            wallet.put("paymentRequired", false);
            wallet.put("status", "CHECK_DISABLED");
            wallet.put("message", "Wallet guard is disabled.");
            return wallet;
        }

        UUID parsedTenantId = parseTenantId(tenantId);
        if (parsedTenantId == null) {
            wallet.put("checked", false);
            wallet.put("available", false);
            wallet.put("paymentRequired", false);
            wallet.put("status", defaultString(tenantId, "").isBlank() ? "MISSING_TENANT" : "INVALID_TENANT");
            wallet.put("message", "X-Tenant-ID is required to check wallet readiness.");
            return wallet;
        }

        try {
            BillingWalletService.WalletBalanceResponse balance = billingWalletService.getWalletBalanceDetails(parsedTenantId);
            BigDecimal currentBalance = balance.balance() == null ? BigDecimal.ZERO : balance.balance();
            boolean available = currentBalance.compareTo(minimumBalance) >= 0;
            wallet.put("checked", true);
            wallet.put("available", available);
            wallet.put("paymentRequired", !available);
            wallet.put("status", available ? "OK" : "LOW_BALANCE");
            wallet.put("balance", currentBalance);
            wallet.put("currency", defaultString(balance.currency(), properties.getAi().getBilling().getCurrency()));
            wallet.put("message", available
                    ? "Wallet balance is ready for paid creator actions."
                    : "Wallet balance is below the minimum required for paid creator actions.");
            return wallet;
        } catch (BillingWalletService.WalletBalanceCheckException ex) {
            wallet.put("checked", false);
            wallet.put("available", false);
            wallet.put("paymentRequired", false);
            wallet.put("status", "CHECK_ERROR");
            wallet.put("message", "Unable to verify wallet balance.");
            wallet.put("walletCheckError", safeErrorMessage(ex));
            return wallet;
        }
    }

    private ProviderCreditStatus checkLuma() {
        BigDecimal threshold = positiveDecimal(properties.getAi().getLumaLowCreditThresholdUsd(), BigDecimal.TEN);
        String apiKey = trim(properties.getAi().getLumaApiKey());
        if (apiKey.isBlank()) {
            return missingKey("luma", "Luma Modify Video", "LUMA_API_KEY or CREATOR_LUMA_API_KEY is not configured.",
                    Map.of("thresholdUsd", threshold));
        }
        if (!properties.getAi().isProviderCreditCheckEnabled()) {
            return disabledCheck("luma", "Luma Modify Video", Map.of("thresholdUsd", threshold));
        }
        try {
            JsonNode response = getJson(
                    trimTrailingSlash(defaultString(properties.getAi().getLumaBaseUrl(), "https://api.lumalabs.ai/dream-machine/v1")) + "/credits",
                    apiKey,
                    null,
                    "Luma credits"
            );
            BigDecimal cents = firstDecimal(response, "credit_balance", "creditBalance", "credits_balance");
            BigDecimal usd = cents.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            boolean low = usd.compareTo(threshold) < 0;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("balanceCents", cents);
            details.put("balanceUsd", usd);
            details.put("thresholdUsd", threshold);
            return new ProviderCreditStatus(
                    "luma",
                    "Luma Modify Video",
                    true,
                    !low,
                    low ? "LOW_CREDIT" : "OK",
                    low,
                    low
                            ? "Luma provider credits are low. Add credits before running video polish."
                            : "Luma provider credits are available.",
                    details
            );
        } catch (RuntimeException ex) {
            return errorStatus("luma", "Luma Modify Video", ex, Map.of("thresholdUsd", threshold));
        }
    }

    private ProviderCreditStatus checkRunway() {
        BigDecimal threshold = positiveDecimal(properties.getAi().getRunwayLowCreditThresholdCredits(), BigDecimal.valueOf(100));
        String apiKey = trim(properties.getAi().getRunwayApiSecret());
        if (apiKey.isBlank()) {
            return missingKey("runway", "Runway Aleph 2", "RUNWAY_API_SECRET or CREATOR_RUNWAY_API_SECRET is not configured.",
                    Map.of("thresholdCredits", threshold));
        }
        if (!properties.getAi().isProviderCreditCheckEnabled()) {
            return disabledCheck("runway", "Runway Aleph 2", Map.of("thresholdCredits", threshold));
        }
        try {
            JsonNode response = getJson(
                    trimTrailingSlash(defaultString(properties.getAi().getRunwayBaseUrl(), "https://api.dev.runwayml.com")) + "/v1/organization",
                    apiKey,
                    properties.getAi().getRunwayApiVersion(),
                    "Runway organization"
            );
            BigDecimal credits = firstDecimal(response, "creditBalance", "credit_balance", "credits");
            BigDecimal estimatedUsd = credits.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
            boolean low = credits.compareTo(threshold) < 0;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("creditBalance", credits);
            details.put("estimatedBalanceUsd", estimatedUsd);
            details.put("thresholdCredits", threshold);
            return new ProviderCreditStatus(
                    "runway",
                    "Runway Aleph 2",
                    true,
                    !low,
                    low ? "LOW_CREDIT" : "OK",
                    low,
                    low
                            ? "Runway provider credits are low. Add credits before running video polish."
                            : "Runway provider credits are available.",
                    details
            );
        } catch (RuntimeException ex) {
            return errorStatus("runway", "Runway Aleph 2", ex, Map.of("thresholdCredits", threshold));
        }
    }

    private ProviderCreditStatus googleVeoStatus() {
        boolean configured = !trim(properties.getAi().getGeminiApiKey()).isBlank();
        boolean disabled = properties.getAi().isProviderCreditCheckEnabled() && !configured;
        return new ProviderCreditStatus(
                "google_veo",
                "Google Veo",
                configured,
                configured,
                configured ? "UNKNOWN_CREDIT" : "MISSING_KEY",
                disabled,
                configured
                        ? "Google Veo quota is managed by Google; no provider credit balance endpoint is configured here."
                        : "GEMINI_API_KEY or CREATOR_GEMINI_API_KEY is not configured.",
                Map.of()
        );
    }

    private ProviderCreditStatus decartStatus() {
        boolean configured = !trim(properties.getAi().getDecartApiKey()).isBlank();
        boolean disabled = properties.getAi().isProviderCreditCheckEnabled() && !configured;
        return new ProviderCreditStatus(
                "decart",
                "Decart Lucy VTON",
                configured,
                configured,
                configured ? "UNKNOWN_CREDIT" : "MISSING_KEY",
                disabled,
                configured
                        ? "Decart key is configured. Decart credit balance is verified by the provider render call."
                        : "DECART_API_KEY or CREATOR_DECART_API_KEY is not configured.",
                Map.of("model", properties.getAi().getDecartVideoModel())
        );
    }

    private ProviderCreditStatus missingKey(String provider, String label, String message, Map<String, Object> details) {
        return new ProviderCreditStatus(
                provider,
                label,
                false,
                false,
                "MISSING_KEY",
                properties.getAi().isProviderCreditCheckEnabled(),
                message,
                details
        );
    }

    private ProviderCreditStatus disabledCheck(String provider, String label, Map<String, Object> details) {
        return new ProviderCreditStatus(
                provider,
                label,
                true,
                true,
                "CHECK_DISABLED",
                false,
                "Provider credit check is disabled.",
                details
        );
    }

    private ProviderCreditStatus errorStatus(String provider, String label, RuntimeException ex, Map<String, Object> details) {
        Map<String, Object> mergedDetails = new LinkedHashMap<>(details == null ? Map.of() : details);
        mergedDetails.put("creditCheckWarning", true);
        mergedDetails.put("creditCheckError", safeErrorMessage(ex));
        return new ProviderCreditStatus(
                provider,
                label,
                true,
                true,
                "CHECK_ERROR",
                false,
                "Could not verify " + label + " provider credits. Polish is still enabled; the provider render call will verify the key and balance.",
                mergedDetails
        );
    }

    private JsonNode getJson(String url, String bearerToken, String runwayVersion, String label) {
        try {
            WebClient.RequestHeadersSpec<?> request = webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json");
            if (runwayVersion != null && !runwayVersion.isBlank()) {
                request.header("X-Runway-Version", runwayVersion);
            }
            return request
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 60000)));
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("%s request failed HTTP %s.".formatted(label, ex.getStatusCode()), ex);
        }
    }

    private BigDecimal firstDecimal(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual() && !value.asText("").isBlank()) {
                try {
                    return new BigDecimal(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // Try the next name.
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private String normalizeProvider(String provider) {
        String normalized = defaultString(provider, properties.getAi().getStudioPolishVideoProvider())
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .trim();
        if (normalized.equals("veo") || normalized.equals("google") || normalized.equals("gemini_veo")) {
            return "google_veo";
        }
        if (normalized.equals("luma_modify_video") || normalized.equals("luma_ai")) {
            return "luma";
        }
        if (normalized.equals("runway_aleph") || normalized.equals("runwayml")) {
            return "runway";
        }
        if (normalized.equals("decart_vton") || normalized.equals("lucy_vton") || normalized.equals("lucy_vton_3")) {
            return "decart";
        }
        return normalized.isBlank() ? "google_veo" : normalized;
    }

    private BigDecimal positiveDecimal(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private String safeErrorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private String trimTrailingSlash(String value) {
        String normalized = trim(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private UUID parseTenantId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ProviderCreditStatus(
            String provider,
            String label,
            boolean configured,
            boolean available,
            String status,
            boolean polishDisabled,
            String message,
            Map<String, Object> details
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("provider", provider);
            map.put("label", label);
            map.put("configured", configured);
            map.put("available", available);
            map.put("status", status);
            map.put("polishDisabled", polishDisabled);
            map.put("message", message);
            if (details != null) {
                map.putAll(details);
            }
            return map;
        }
    }
}
