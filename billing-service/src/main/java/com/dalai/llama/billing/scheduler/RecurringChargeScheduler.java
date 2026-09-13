package com.dalai.llama.billing.scheduler;

import com.dalai.llama.billing.domain.entity.RecurringCharge;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.event.RecurringChargeOutcomeEvent;
import com.dalai.llama.billing.domain.exception.InsufficientBalanceException;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final BillingEventProducer eventProducer;

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
        String reference = String.format("%s:%s", charge.getType(),
                charge.getDescription() != null ? charge.getDescription() : charge.getId());

        try {
            walletService.debit(charge.getTenantId(), charge.getAmount(), chargeType(charge), reference,
                    charge.getSubscriptionId(), null, charge.getDescription(), null);
        } catch (InsufficientBalanceException ex) {
            // Balance really is short -- debit() refuses rather than going negative. Leave
            // nextChargeDate untouched so tomorrow's run retries the same charge (self-healing
            // once the tenant tops up); just report the miss for whoever owns subscriptionId to
            // react to (e.g. product-service dropping a creator-video subscription's entitlements).
            log.warn("Recurring charge {} failed for tenant {} - insufficient balance: {}",
                    charge.getId(), charge.getTenantId(), ex.getMessage());
            publishOutcome(charge, false, ex.getMessage());
            return false;
        }

        // Update charge
        charge.markCharged();
        chargeRepository.save(charge);

        // Evaluate billing state (may trigger GRACE or SUSPENDED) -- PBX charges only care about
        // this; subscription-linked charges react to the outcome event below instead.
        billingStateService.evaluateState(charge.getTenantId());

        publishOutcome(charge, true, null);

        log.info("Processed recurring charge {} for tenant {}: ₹{}",
                charge.getType(), charge.getTenantId(), charge.getAmount());

        return true;
    }

    private void publishOutcome(RecurringCharge charge, boolean succeeded, String failureReason) {
        if (charge.getSubscriptionId() == null) {
            return; // No subscription lifecycle to react to (plain PBX platform/DID/agent fee).
        }
        eventProducer.publishRecurringChargeOutcome(RecurringChargeOutcomeEvent.builder()
                .recurringChargeId(charge.getId())
                .tenantId(charge.getTenantId())
                .subscriptionId(charge.getSubscriptionId())
                .chargeType(charge.getType())
                .amount(charge.getAmount())
                .succeeded(succeeded)
                .failureReason(failureReason)
                .occurredAt(Instant.now())
                .build());
    }

    private TransactionType chargeType(RecurringCharge charge) {
        return "DID_RENTAL".equals(charge.getType()) ? TransactionType.DID_RENTAL : TransactionType.SUBSCRIPTION;
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