package com.dalai.llama.preprod.service.revenue;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** pre-production-service -> billing-service for the client pay-to-lock flow. billing owns all
 * pricing/Razorpay/wallet logic (SRP); this is a thin caller of its internal endpoints, same
 * service-mesh convention as every other cross-service client here. */
@Component
public class BillingClient {

    public record Quote(BigDecimal platformBase, BigDecimal creatorAmount, BigDecimal totalAmount, String currency, BigDecimal creatorMarginPercent) {}

    public record OrderResult(UUID paymentId, String gatewayOrderId, BigDecimal amount, String currency, String keyId) {}

    public record VerifyResult(UUID paymentId, UUID projectId, UUID tenantId, BigDecimal creatorAmount, boolean success) {}

    private record CreateOrderRequest(String reviewToken) {}

    private record VerifyRequest(String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {}

    private final WebClient webClient;
    private final int timeoutMs;

    public BillingClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.billing.base-url}") String baseUrl,
            @Value("${pre-production.billing.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public Quote quote(UUID tenantId, UUID projectId) {
        return post("/api/v1/internal/tenants/%s/client-review/%s/quote".formatted(tenantId, projectId), null, Quote.class);
    }

    public OrderResult createOrder(UUID tenantId, UUID projectId, String reviewToken) {
        return post("/api/v1/internal/tenants/%s/client-review/%s/order".formatted(tenantId, projectId),
                new CreateOrderRequest(reviewToken), OrderResult.class);
    }
    /** Extra-review (beyond the project's allowance) pricing/order -- billing owns the flat price. */
    public Quote reviewQuote(UUID tenantId, UUID projectId) {
        return post("/api/v1/internal/tenants/%s/extra-review/%s/quote".formatted(tenantId, projectId), null, Quote.class);
    }
    public OrderResult createReviewOrder(UUID tenantId, UUID projectId, String reviewToken) {
        return post("/api/v1/internal/tenants/%s/extra-review/%s/order".formatted(tenantId, projectId),
                new CreateOrderRequest(reviewToken), OrderResult.class);
    }

    public VerifyResult verify(UUID tenantId, String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {
        return post("/api/v1/internal/tenants/%s/client-review/verify".formatted(tenantId),
                new VerifyRequest(gatewayOrderId, gatewayPaymentId, gatewaySignature), VerifyResult.class);
    }

    private <T> T post(String uri, Object body, Class<T> type) {
        try {
            WebClient.RequestBodySpec spec = webClient.post().uri(uri);
            return (body == null ? spec : spec.bodyValue(body))
                    .retrieve()
                    .bodyToMono(type)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "billing-service call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
