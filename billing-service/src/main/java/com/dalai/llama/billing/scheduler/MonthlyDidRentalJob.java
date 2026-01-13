package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonthlyDidRentalJob {

    private final UsageService usageService;

    @Scheduled(cron = "0 0 0 1 * *")
    public void chargeMonthlyDidRentals() {
        usageService.chargeMonthlyDidRentals();
    }
}
