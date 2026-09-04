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
 * The real Razorpay charge behind the public brief page's "fund this brief" button --
 * billing-service's {@code InternalProjectRequirementPaymentController}, the no-JWT counterpart
 * of its tenant-authenticated {@code PaymentController} (unreachable here since the brief's
 * visitor has no account). Same package-private, gating-call shape as {@link
 * BillingServiceClient}: a failure here must fail the request, never silently pretend a charge
 * went through.
 */
@Slf4j
@Component
class RequirementFundingBillingClient {

    record OrderResult(UUID paymentId, String gatewayOrderId, BigDecimal amount, String currency, String keyId, String status) {}

    private record CreateOrderRequest(String currency, BigDecimal amount, String description) {}

    private record VerifyRequest(String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {}

    private final WebClient webClient;
    private final int timeoutMs;

    RequirementFundingBillingClient(
            WebClient.Builder webClientBuilder,
            @Value("${creative-planning.billing.base-url}") String baseUrl,
            @Value("${creative-planning.billing.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    OrderResult createOrder(UUID tenantId, UUID requirementId, BigDecimal amount, String currency, String description) {
        return post("/api/v1/internal/tenants/%s/project-requirement-payments/%s/order".formatted(tenantId, requirementId),
                new CreateOrderRequest(currency, amount, description), OrderResult.class);
    }

    void verify(UUID tenantId, UUID paymentId, String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {
        webClient.post()
                .uri("/api/v1/internal/tenants/{tenantId}/project-requirement-payments/{paymentId}/verify", tenantId, paymentId)
                .bodyValue(new VerifyRequest(gatewayOrderId, gatewayPaymentId, gatewaySignature))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofMillis(timeoutMs));
    }

    private <T> T post(String uri, Object body, Class<T> type) {
        try {
            T result = webClient.post().uri(uri).bodyValue(body).retrieve().bodyToMono(type).block(Duration.ofMillis(timeoutMs));
            if (result == null) {
                throw CreativePlanningException.upstream("billing-service returned no response for " + uri);
            }
            return result;
        } catch (WebClientResponseException ex) {
            log.error("billing-service call failed uri={} status={} body={}", uri, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw CreativePlanningException.upstream("billing-service could not process this payment: " + ex.getStatusCode());
        } catch (CreativePlanningException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("billing-service call failed uri={}: {}", uri, ex.getMessage(), ex);
            throw CreativePlanningException.upstream("billing-service is unreachable: " + ex.getMessage());
        }
    }
}
