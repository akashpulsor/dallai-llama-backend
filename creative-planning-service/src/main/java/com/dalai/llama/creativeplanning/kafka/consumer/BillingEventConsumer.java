package com.dalai.llama.creativeplanning.kafka.consumer;

import com.dalai.llama.creativeplanning.domain.event.PaymentReceivedEvent;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The billing -> creative-planning half of the funded-brief handoff. Only reacts to payments
 * billing-service tagged with a {@code projectRequirementId} -- a plain wallet top-up or
 * subscription payment publishes the same event shape with that field null and is ignored here. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final ProjectRequirementService projectRequirementService;

    @KafkaListener(
            topics = "billing.payment.received",
            groupId = "creative-planning-service",
            containerFactory = "paymentReceivedListenerFactory"
    )
    public void onPaymentReceived(PaymentReceivedEvent event) {
        if (event.getProjectRequirementId() == null) {
            return;
        }
        log.info("Payment {} funded project requirement {} (tenant {})",
                event.getPaymentId(), event.getProjectRequirementId(), event.getTenantId());
        projectRequirementService.markFundedFromPayment(
                event.getTenantId(), event.getProjectRequirementId(), event.getPaymentId());
    }
}
