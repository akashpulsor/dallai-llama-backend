package com.dalai.llama.billing.service.payment;

import com.dalai.llama.billing.domain.exception.PaymentFailedException;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class RazorpayService implements PaymentGateway {

    private final RazorpayClient razorpayClient;

    @Value("${razorpay.key-secret}")
    private String razorpayKeySecret;

    /**
     * Create Razorpay order
     */
    @Override
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        try {
            JSONObject request = new JSONObject();
            request.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue()); // major → minor
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

}
