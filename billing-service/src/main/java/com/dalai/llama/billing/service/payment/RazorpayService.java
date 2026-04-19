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

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayService implements PaymentGateway {

    private final RazorpayClient razorpayClient;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.key-secret}")
    private String razorpayKeySecret;

    /**
     * Create Razorpay order
     */
    @Override
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue());
            request.put("currency", currency);
            request.put("receipt", receipt);

            Order order = razorpayClient.orders.create(request);
            return order.get("id");

        } catch (RazorpayException e) {
            throw new PaymentFailedException("Failed to create Razorpay order", e);
        }
    }

    /**
     * Verify Razorpay payment signature
     */
    @Override
    public void verify(String orderId, String paymentId, String signature) {
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
     * Create refund for captured payment
     */
    public String initiateRefund(String paymentId, BigDecimal amount) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount",
                    amount.multiply(BigDecimal.valueOf(100)).intValue());

            var refund = razorpayClient.payments
                    .refund(paymentId, request);

            String refundId = refund.get("id");

            log.info("Refund initiated for payment {} refund {}",
                    paymentId,
                    refundId);

            return refundId;

        } catch (Exception e) {
            throw new PaymentFailedException(
                    "Failed to initiate Razorpay refund",
                    e
            );
        }
    }

    /**
     * Extract event type from webhook payload
     */
    public String extractEventType(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            return event.path("event").asText();
        } catch (Exception e) {
            throw new PaymentFailedException(
                    "Invalid webhook payload",
                    e
            );
        }
    }

    /**
     * Extract payment entity from webhook payload
     */
    public JsonNode extractPaymentEntity(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);

            return event.path("payload")
                    .path("payment")
                    .path("entity");

        } catch (Exception e) {
            throw new PaymentFailedException(
                    "Invalid payment webhook payload",
                    e
            );
        }
    }

    /**
     * Extract refund entity from webhook payload
     */
    public JsonNode extractRefundEntity(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);

            return event.path("payload")
                    .path("refund")
                    .path("entity");

        } catch (Exception e) {
            throw new PaymentFailedException(
                    "Invalid refund webhook payload",
                    e
            );
        }
    }
}