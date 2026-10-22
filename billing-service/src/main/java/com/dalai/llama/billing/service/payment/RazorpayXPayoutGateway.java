package com.dalai.llama.billing.service.payment;

import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RazorpayX Payouts over its REST API (razorpay-java has no payouts client). Same API key as
 * checkout; {@code razorpayx.account-number} is the RazorpayX business account the money leaves
 * from. With {@code razorpay.mock-enabled} nothing is called and every payout is processed at once.
 */
@Slf4j
@Component
public class RazorpayXPayoutGateway implements PayoutGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final String accountNumber;
    private final boolean mockEnabled;

    public RazorpayXPayoutGateway(
            WebClient.Builder webClientBuilder,
            @Value("${razorpay.key-id}") String keyId,
            @Value("${razorpay.key-secret}") String keySecret,
            @Value("${razorpayx.base-url:https://api.razorpay.com/v1}") String baseUrl,
            @Value("${razorpayx.account-number:}") String accountNumber,
            @Value("${razorpay.mock-enabled:false}") boolean mockEnabled
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(keyId, keySecret))
                .build();
        this.accountNumber = accountNumber;
        this.mockEnabled = mockEnabled;
    }

    @Override
    public String createContact(UUID tenantId, String name) {
        if (mockEnabled) {
            return mockId("cont_");
        }
        return post("/contacts", Map.of("name", name, "type", "vendor", "reference_id", tenantId.toString()), null)
                .path("id").asText();
    }

    @Override
    public String createFundAccount(String contactId, PayoutMethod method, String holderName, String ifsc,
                                    String accountNumber, String vpa) {
        if (mockEnabled) {
            return mockId("fa_");
        }
        Map<String, Object> body = method == PayoutMethod.UPI
                ? Map.of("contact_id", contactId, "account_type", "vpa", "vpa", Map.of("address", vpa))
                : Map.of("contact_id", contactId, "account_type", "bank_account",
                        "bank_account", Map.of("name", holderName, "ifsc", ifsc, "account_number", accountNumber));
        return post("/fund_accounts", body, null).path("id").asText();
    }

    @Override
    public PayoutResult createPayout(String fundAccountId, PayoutMethod method, BigDecimal amount, String referenceId) {
        if (mockEnabled) {
            log.info("[MOCK] RazorpayX payout fundAccount={} amount={} reference={}", fundAccountId, amount, referenceId);
            return new PayoutResult(mockId("pout_"), "processed", "MOCKUTR" + referenceId.substring(0, 8));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", accountNumber);
        body.put("fund_account_id", fundAccountId);
        body.put("amount", amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
        body.put("currency", "INR");
        body.put("mode", method == PayoutMethod.UPI ? "UPI" : "IMPS");
        body.put("purpose", "payout");
        body.put("queue_if_low_balance", true);
        body.put("reference_id", referenceId);
        body.put("narration", "Dalai Llama payout");
        JsonNode payout = post("/payouts", body, referenceId);
        return new PayoutResult(payout.path("id").asText(), payout.path("status").asText(), textOrNull(payout.path("utr")));
    }

    private JsonNode post(String path, Object body, String idempotencyKey) {
        try {
            return webClient.post().uri(path)
                    .headers(headers -> {
                        if (idempotencyKey != null) {
                            headers.set("X-Payout-Idempotency", idempotencyKey);
                        }
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new PayoutRejectedException("RazorpayX refused " + path + ": " + ex.getResponseBodyAsString());
            }
            throw ex;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private static String mockId(String prefix) {
        return prefix + "MOCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
