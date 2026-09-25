package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Prices a standalone requirement's video duration -- billing-service owns the actual rate
 * (platform per-second cost) and the creator's own margin, this client just asks for a quote. A
 * gating call, same reasoning as {@link PreProductionServiceClient}: a standalone brief always
 * needs a real price, so a billing-service failure here must fail the create request rather than
 * saving a requirement with no price.
 */
@Slf4j
@Component
class BillingServiceClient {

    /** Mirrors billing-service's {@code VideoPricingService.Quote} field for field -- no shared
     * module between the two services, so this is a structural copy, Jackson matches by field
     * name regardless (same convention as billing-service's own LlmBillingEvent). Only {@code
     * platformCost} and {@code totalPrice} are actually read on this side today; the per-component
     * breakdown fields are still mirrored so this stays a faithful structural copy rather than a
     * silently-lossy one. */
    record VideoPriceQuote(
            int durationSeconds,
            int estimatedShotCount,
            BigDecimal videoCost,
            BigDecimal imageCost,
            BigDecimal visionAnalysisCost,
            BigDecimal critiqueCost,
            BigDecimal scriptAndScreenplayCost,
            BigDecimal platformCost,
            BigDecimal creatorMarginPercent,
            BigDecimal creatorAmount,
            BigDecimal totalPrice,
            String currency
    ) {}

    private final WebClient webClient;
    private final int timeoutMs;

    BillingServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.billing.base-url}") String baseUrl,
            @Value("${creative-planning.billing.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    /** Structural mirror of billing-service's {@code WalletBalanceResponse} -- only {@code
     * balance} is read here (the gate is simply "any balance > 0"), the other fields carry
     * through faithfully so this stays a lossless copy. */
    record WalletBalanceView(BigDecimal balance, String currency, Boolean withinCap, BigDecimal totalSpent) {}

    /** Simple wallet-balance read used by the "creator can proceed with generation while wallet
     * has any money" bypass (see ProjectRequirementIdeaService.buildChatRequest). Returns null
     * on a billing-service failure -- the caller treats that as "no bypass, fall back to the
     * hard funded check" rather than blocking generation on a transient wallet-lookup failure. */
    BigDecimal getWalletBalance(UUID tenantId) {
        try {
            WalletBalanceView view = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/tenants/{tenantId}/wallet/balance")
                            .build(tenantId))
                    .retrieve()
                    .bodyToMono(WalletBalanceView.class)
                    .block(Duration.ofMillis(timeoutMs));
            return view == null ? null : view.balance();
        } catch (Exception ex) {
            log.warn("Could not read wallet balance for tenant {}: {}", tenantId, ex.getMessage());
            return null;
        }
    }

    VideoPriceQuote quoteVideoPrice(UUID tenantId, int durationSeconds) {
        try {
            VideoPriceQuote quote = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/tenants/{tenantId}/video-pricing/quote")
                            .queryParam("durationSeconds", durationSeconds)
                            .build(tenantId))
                    .retrieve()
                    .bodyToMono(VideoPriceQuote.class)
                    .block(Duration.ofMillis(timeoutMs));

            if (quote == null) {
                throw CreativePlanningException.upstream("billing-service returned no price quote for tenant " + tenantId);
            }
            return quote;
        } catch (WebClientResponseException ex) {
            log.error("billing-service rejected video price quote for tenant {} status={} body={}",
                    tenantId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw CreativePlanningException.upstream("billing-service could not price this requirement: " + ex.getStatusCode());
        } catch (CreativePlanningException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("billing-service call failed pricing tenant {}: {}", tenantId, ex.getMessage(), ex);
            throw CreativePlanningException.upstream("billing-service is unreachable: " + ex.getMessage());
        }
    }
}
