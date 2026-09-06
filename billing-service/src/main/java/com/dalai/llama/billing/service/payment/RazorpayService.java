package com.dalai.llama.billing.service.payment;

import com.dalai.llama.billing.domain.exception.PaymentFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayService implements PaymentGateway {

    private final RazorpayClient razorpayClient;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${razorpay.webhook-secret}")
    private String razorpayWebhookSecret;

    @Value("${razorpay.mock-enabled:false}")
    private boolean mockEnabled;

    @Override
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        if (mockEnabled) {
            String mockOrderId = "order_MOCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            log.info("[MOCK] Razorpay order created: id={} amount={} currency={} receipt={}",
                    mockOrderId, amount, currency, receipt);
            return mockOrderId;
        }

        try {
            int amountPaise = toPaise(amount);
            if (amountPaise < 100) {
                throw new IllegalArgumentException("Minimum Razorpay order amount is 100 paise");
            }

            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);
            request.put("currency", currency);
            request.put("receipt", receipt);

            Order order = razorpayClient.orders.create(request);
            return order.get("id");

        } catch (RazorpayException e) {
            throw new PaymentFailedException("Failed to create Razorpay order", e);
        }
    }

    @Override
    public void verify(String orderId, String paymentId, String signature) {
        if (mockEnabled) {
            log.info("[MOCK] Skipping signature verification for order={} payment={}", orderId, paymentId);
            return;
        }

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", orderId);
            attributes.put("razorpay_payment_id", paymentId);
            attributes.put("razorpay_signature", signature);

            Utils.verifyPaymentSignature(attributes, razorpayKeySecret);

        } catch (Exception e) {
            throw new PaymentFailedException("Invalid Razorpay payment signature", e);
        }
    }

    /**
     * Verifies the X-Razorpay-Signature header on an incoming webhook against the raw
     * request body, using the webhook secret configured in the Razorpay dashboard (distinct
     * from the API key/secret used for the checkout flow -- see {@link #verify}).
     */
    public void verifyWebhookSignature(String payload, String signature) {
        if (mockEnabled) {
            log.info("[MOCK] Skipping webhook signature verification");
            return;
        }

        try {
            Utils.verifyWebhookSignature(payload, signature, razorpayWebhookSecret);
        } catch (Exception e) {
            throw new PaymentFailedException("Invalid Razorpay webhook signature", e);
        }
    }

    public String initiateRefund(String paymentId, BigDecimal amount) {
        if (mockEnabled) {
            String mockRefundId = "rfnd_MOCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            log.info("[MOCK] Razorpay refund initiated: refund={} payment={} amount={}",
                    mockRefundId, paymentId, amount);
            return mockRefundId;
        }

        try {
            JSONObject request = new JSONObject();
            request.put("amount", toPaise(amount));

            var refund = razorpayClient.payments.refund(paymentId, request);
            String refundId = refund.get("id");

            log.info("Refund initiated for payment {} refund {}", paymentId, refundId);
            return refundId;

        } catch (Exception e) {
            throw new PaymentFailedException("Failed to initiate Razorpay refund", e);
        }
    }

    // extractEventType, extractPaymentEntity, extractRefundEntity — unchanged
    public String extractEventType(String payload) {
        try {
            return objectMapper.readTree(payload).path("event").asText();
        } catch (Exception e) {
            throw new PaymentFailedException("Invalid webhook payload", e);
        }
    }

    public JsonNode extractPaymentEntity(String payload) {
        try {
            return objectMapper.readTree(payload).path("payload").path("payment").path("entity");
        } catch (Exception e) {
            throw new PaymentFailedException("Invalid payment webhook payload", e);
        }
    }

    public JsonNode extractRefundEntity(String payload) {
        try {
            return objectMapper.readTree(payload).path("payload").path("refund").path("entity");
        } catch (Exception e) {
            throw new PaymentFailedException("Invalid refund webhook payload", e);
        }
    }

    private int toPaise(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
