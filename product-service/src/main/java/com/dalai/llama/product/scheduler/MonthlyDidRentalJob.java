package com.dalai.llama.product.scheduler;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.repository.DidRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monthly job to charge DID rental fees.
 * Runs on 1st of each month at 00:30 AM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyDidRentalJob {

    private final DidRepository didRepository;
    private final BillingServiceClient billingServiceClient;

    private static final BigDecimal DEFAULT_MONTHLY_RATE = new BigDecimal("500.00");

    /**
     * Runs on 1st of every month at 00:30 AM
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "0 30 0 1 * *")
    public void chargeMonthlyDidRentals() {
        log.info("Starting monthly DID rental billing job for {}", LocalDate.now());

        try {
            // Get all ACTIVE DIDs
            List<Did> activeDids = didRepository.findByStatus(DidStatus.ACTIVE);
            log.info("Found {} active DIDs to bill", activeDids.size());

            if (activeDids.isEmpty()) {
                log.info("No active DIDs to bill");
                return;
            }

            // Group DIDs by tenant
            Map<UUID, List<Did>> didsByTenant = activeDids.stream()
                    .collect(Collectors.groupingBy(Did::getTenantId));

            int successCount = 0;
            int failCount = 0;

            // Process each tenant
            for (Map.Entry<UUID, List<Did>> entry : didsByTenant.entrySet()) {
                UUID tenantId = entry.getKey();
                List<Did> tenantDids = entry.getValue();

                log.info("Billing tenant {} for {} DIDs", tenantId, tenantDids.size());

                for (Did did : tenantDids) {
                    try {
                        chargeDid(tenantId, did);
                        successCount++;
                    } catch (Exception e) {
                        log.error("Failed to charge DID {} for tenant {}: {}", 
                                did.getNumber(), tenantId, e.getMessage());
                        failCount++;
                    }
                }
            }

            log.info("Monthly DID rental billing completed. Success: {}, Failed: {}", 
                    successCount, failCount);

        } catch (Exception e) {
            log.error("Monthly DID rental billing job failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Charge rental for a single DID
     */
    private void chargeDid(UUID tenantId, Did did) {
        BigDecimal monthlyRate = did.getMonthlyRental();
        
        // Use default rate if not set on DID
        if (monthlyRate == null || monthlyRate.signum() <= 0) {
            monthlyRate = DEFAULT_MONTHLY_RATE;
            log.warn("DID {} has no monthly rate set, using default: {}", 
                    did.getNumber(), DEFAULT_MONTHLY_RATE);
        }

        log.debug("Charging DID {} rental {} for tenant {}", 
                did.getNumber(), monthlyRate, tenantId);

        // Call Billing Service to record the charge
        billingServiceClient.recordDidRental(
                tenantId,
                did.getId(),
                did.getNumber(),
                monthlyRate
        );

        log.info("Charged DID {} rental {} for tenant {}", 
                did.getNumber(), monthlyRate, tenantId);
    }

    /**
     * Manual trigger for testing or re-billing
     */
    public void chargeDidRentalForTenant(UUID tenantId) {
        log.info("Manual DID rental billing for tenant {}", tenantId);

        List<Did> tenantDids = didRepository.findByTenantId(tenantId).stream()
                .filter(d -> d.getStatus() == DidStatus.ACTIVE)
                .toList();

        for (Did did : tenantDids) {
            chargeDid(tenantId, did);
        }

        log.info("Completed manual DID rental billing for tenant {}", tenantId);
    }
}
