package com.dalai.llama.product.kafka.consumer;



import com.dalai.llama.product.domain.event.WalletDeductedForSubscriptionEvent;
import com.dalai.llama.product.service.impl.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final SubscriptionService subscriptionService;

    @KafkaListener(
            topics = "billing.wallet.debited",
            groupId = "billing-service",
            containerFactory = "walletDeductedListenerFactory"
    )
    public void onWalletDeducted(WalletDeductedForSubscriptionEvent event) {
        log.info("Received WalletDeductedForSubscriptionEvent: subId={}, eventId={}",
                event.subscriptionId(), event.eventId());
        subscriptionService.processWalletDeducted(event);
    }
}