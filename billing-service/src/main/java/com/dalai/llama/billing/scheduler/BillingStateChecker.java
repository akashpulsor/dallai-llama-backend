package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.service.BillingStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BillingStateChecker {

    private final BillingStateService billingStateService;

    @Scheduled(fixedRate = 60_000)
    public void checkGraceExpiry() {
        billingStateService.transitionExpiredGraceTenants();
    }

    @Scheduled(fixedRate = 60_000)
    public void checkLowBalance() {
        billingStateService.detectLowBalanceWallets();
    }
}
