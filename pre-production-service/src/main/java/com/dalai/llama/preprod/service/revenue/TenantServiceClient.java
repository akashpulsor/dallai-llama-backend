package com.dalai.llama.preprod.service.revenue;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** tenant-service's real internal tenant lookup (no JWT, service-mesh-trusted, same convention
 * as every other internal cross-service call here) -- used only to read {@code marginPercent},
 * the creator's own markup on the platform's standard rate. */
@Component
public class TenantServiceClient {

    public record TenantSummary(UUID id, String name, BigDecimal marginPercent) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    public TenantServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.tenant.base-url}") String baseUrl,
            @Value("${pre-production.tenant.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public BigDecimal getMarginPercent(UUID tenantId) {
        try {
            TenantSummary tenant = webClient.get()
                    .uri("/api/v1/internal/tenants/{id}", tenantId)
                    .retrieve()
                    .bodyToMono(TenantSummary.class)
                    .block(Duration.ofMillis(timeoutMs));
            return tenant != null && tenant.marginPercent() != null ? tenant.marginPercent() : BigDecimal.ZERO;
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "tenant-service lookup failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }
}
