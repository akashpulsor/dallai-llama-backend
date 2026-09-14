package com.dalai.llama.product.dto.creatorvideo;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
public record CreatorVideoSubscriptionResponse(
        UUID subscriptionId,
        /** Subscription status, or one of: PAYMENT_REQUIRED (an order is waiting to be paid),
         *  PAYMENT_UNAVAILABLE (the order could not be created -- see {@link #message}). */
        String status,
        String planCode,
        String planName,
        String billingCycle,
        BigDecimal price,
        String currency,
        Instant currentPeriodEnd,
        // Populated when the wallet cannot cover the plan.
        BigDecimal currentWalletBalance,
        BigDecimal shortFallAmount,
        /** Everything the browser needs to open Razorpay checkout for the shortfall, when
         * status = PAYMENT_REQUIRED. The order is created here rather than left to the caller:
         * a client that has to work out how much to charge, create its own order and then retry
         * is a client that can get any of those three steps wrong, which is exactly what kept
         * happening. */
        UUID paymentId,
        String gatewayOrderId,
        String razorpayKeyId,
        BigDecimal amountDue,
        /** Why a subscribe could not proceed, in words the creator can act on. Set when the
         *  status is not something the browser can simply carry out -- never a stack trace or
         *  an upstream status code. */
        String message
) {
}
