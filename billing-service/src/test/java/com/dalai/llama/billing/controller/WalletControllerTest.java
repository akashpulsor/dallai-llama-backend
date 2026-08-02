package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.dto.request.RechargeWalletRequest;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WalletController controller = new WalletController(
            mock(TransactionRepository.class),
            paymentService,
            mock(WalletService.class)
    );

    @Test
    void rechargeResponseContainsRazorpayCheckoutContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        RechargeWalletRequest request = objectMapper.readValue(
                "{\"amount\":\"50.00\",\"currency\":\"INR\"}",
                RechargeWalletRequest.class
        );

        when(paymentService.createPaymentOrder(
                eq(tenantId), eq("INR"), eq(new BigDecimal("50.00")),
                eq("Wallet Recharge"), isNull()
        )).thenReturn(new PaymentService.PaymentOrderResult(
                paymentId,
                "order_checkout_123",
                new BigDecimal("50.00"),
                "INR",
                "rzp_test_public_key",
                "PENDING"
        ));

        WalletController.RechargeResponse response = controller.rechargeWallet(tenantId, request).getBody();
        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.path("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(json.path("gatewayOrderId").asText()).isEqualTo("order_checkout_123");
        assertThat(json.path("order_id").asText()).isEqualTo("order_checkout_123");
        assertThat(json.path("amountPaise").asInt()).isEqualTo(5000);
        assertThat(json.path("currency").asText()).isEqualTo("INR");
        assertThat(json.path("keyId").asText()).isEqualTo("rzp_test_public_key");
        JsonNode checkout = json.path("checkoutDetails");
        assertThat(checkout.path("order_id").asText()).isEqualTo("order_checkout_123");
        assertThat(checkout.path("key").asText()).isEqualTo("rzp_test_public_key");
        assertThat(checkout.path("amount").asInt()).isEqualTo(5000);
        assertThat(checkout.path("currency").asText()).isEqualTo("INR");
    }
}
