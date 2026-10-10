package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Component
public class BillingServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final String billingServiceUrl;

    public BillingServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.billing.url:http://product-service:8080}") String billingServiceUrl) {
        this.webClientBuilder = webClientBuilder;
        this.billingServiceUrl = billingServiceUrl;
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(billingServiceUrl).build();
    }

    public boolean hasSufficientBalance(UUID walletId, int minBalance) {
        return client().get()
                .uri("/api/v1/internal/wallets/{id}/balance", walletId)
                .retrieve()
                .bodyToMono(Integer.class)
                .map(balance -> balance >= minBalance)
                .block();
    }

    public void createWallet(UUID tenantId) {
        client().post()
                .uri("/api/v1/internal/tenants/{tenantId}/wallet", tenantId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public void deleteWallet(UUID tenantId) {
        client().post()
                .uri("/api/v1/internal/tenants/{tenantId}/wallet", tenantId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    public BigDecimal getBalance(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/balance", tenantId)
                    .retrieve()
                    .bodyToMono(BigDecimal.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get balance for tenant {}: {}", tenantId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /** Billing's add-on price list (billing owns prices). */
    public record AddonOffer(String code, int quantity, BigDecimal price, String currency) {
    }

    public record AddonPurchase(String code, int quantity, BigDecimal price, String currency, BigDecimal balanceAfter) {
    }

    record AddonPurchaseRequest(String code, String idempotencyKey) {
    }

    public java.util.List<AddonOffer> addonCatalog(UUID tenantId) {
        try {
            AddonOffer[] offers = client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/addons", tenantId)
                    .retrieve()
                    .bodyToMono(AddonOffer[].class)
                    .block(java.time.Duration.ofSeconds(10));
            return offers == null ? java.util.List.of() : java.util.List.of(offers);
        } catch (RuntimeException e) {
            log.warn("Add-on catalog unavailable for tenant {}: {}", tenantId, e.getMessage());
            return java.util.List.of();
        }
    }

    /** Debits the wallet for an add-on. Retrying with the same key never charges twice. */
    public AddonPurchase purchaseAddon(UUID tenantId, String code, String idempotencyKey) {
        try {
            return client().post()
                    .uri("/api/v1/internal/tenants/{tenantId}/wallet/addon-purchase", tenantId)
                    .bodyValue(new AddonPurchaseRequest(code, idempotencyKey))
                    .retrieve()
                    .bodyToMono(AddonPurchase.class)
                    .block(java.time.Duration.ofSeconds(20));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            if (e.getStatusCode().value() == 402) {
                throw new com.dalai.llama.tenant.leadmanagement.outreach.WalletTooLowException();
            }
            throw new com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException("Billing is unavailable right now", e);
        } catch (RuntimeException e) {
            throw new com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException("Billing is unavailable right now", e);
        }
    }
}
