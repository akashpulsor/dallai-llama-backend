package com.dalai.llama.billing.kafka.producer;

import com.dalai.llama.billing.domain.event.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BillingEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishWalletCreated(WalletCreatedEvent event) {
        kafkaTemplate.send("billing.wallet.created", event.getTenantId().toString(), event);
    }

    public void publishWalletFunded(WalletCreditedEvent event) {
        kafkaTemplate.send("billing.wallet.funded", event.getTenantId().toString(), event);
    }

    public void publishBillingStateChanged(BillingStateChangedEvent event) {
        kafkaTemplate.send("billing.state.changed", event.getTenantId().toString(), event);
    }

    public void publishWalletLowBalance(WalletLowBalanceEvent event) {
        kafkaTemplate.send("billing.wallet.low", event.getTenantId().toString(), event);
    }

    public void publishPaymentReceived(PaymentReceivedEvent event) {
        kafkaTemplate.send("billing.payment.received", event.getTenantId().toString(), event);
    }

    public void publishCdrRated(CdrRatedEvent event) {
        kafkaTemplate.send("billing.cdr.rated", event.getTenantId().toString(), event);
    }

    /** Published after successful subscription activation for UI websocket */
    public void publishSubscriptionActivated(SubscriptionActivatedEvent event) {
        kafkaTemplate.send("billing.subscription.activated",
                event.getTenantId().toString(), event);
    }
}
