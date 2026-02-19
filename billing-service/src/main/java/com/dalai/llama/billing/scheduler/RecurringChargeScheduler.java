package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.domain.entity.RecurringCharge;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Scheduled job to process recurring charges.
 * Runs daily at 2 AM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringChargeScheduler {

    private final RecurringChargeRepository chargeRepository;
    private final WalletService walletService;
    private final BillingStateService billingStateService;

    /**
     * Process all due recurring charges
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    @Transactional
    public void processRecurringCharges() {
        LocalDate today = LocalDate.now();
        log.info("Starting recurring charge processing for {}", today);

        List<RecurringCharge> dueCharges = chargeRepository.findDueCharges(today);
        log.info("Found {} due recurring charges", dueCharges.size());

        int success = 0, failed = 0, skipped = 0;

        for (RecurringCharge charge : dueCharges) {
            try {
                boolean processed = processCharge(charge);
                if (processed) {
                    success++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Failed to process charge {} for tenant {}: {}",
                        charge.getId(), charge.getTenantId(), e.getMessage());
                failed++;
            }
        }

        log.info("Recurring charge processing completed. Success: {}, Skipped: {}, Failed: {}",
                success, skipped, failed);
    }

    /**
     * Process a single charge
     * @return true if charged, false if skipped
     */
    private boolean processCharge(RecurringCharge charge) {
        // Check if tenant has sufficient balance
        var balance = walletService.getBalance(charge.getTenantId());

        if (balance.compareTo(charge.getAmount()) < 0) {
            log.warn("Insufficient balance for recurring charge {} - tenant: {}, required: {}, available: {}",
                    charge.getType(), charge.getTenantId(), charge.getAmount(), balance);
            // Don't skip - still debit to trigger GRACE state
        }

        // Debit wallet
        String reference = String.format("%s:%s", charge.getType(),
                charge.getDescription() != null ? charge.getDescription() : charge.getId());

        walletService.debit(charge.getTenantId(), charge.getAmount(), reference,charge.getSubscriptionId());

        // Update charge
        charge.markCharged();
        chargeRepository.save(charge);

        // Evaluate billing state (may trigger GRACE or SUSPENDED)
        billingStateService.evaluateState(charge.getTenantId());

        log.info("Processed recurring charge {} for tenant {}: ₹{}",
                charge.getType(), charge.getTenantId(), charge.getAmount());

        return true;
    }

    /**
     * Manual trigger for a specific tenant (for testing/support)
     */
    @Transactional
    public void processChargesForTenant(java.util.UUID tenantId) {
        log.info("Manual recurring charge processing for tenant {}", tenantId);

        List<RecurringCharge> charges = chargeRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");

        for (RecurringCharge charge : charges) {
            if (!charge.getNextChargeDate().isAfter(LocalDate.now())) {
                processCharge(charge);
            }
        }
    }
}