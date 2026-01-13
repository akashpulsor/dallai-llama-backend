package com.dalai.llama.product.scheduler;

import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.service.didww.DidwwProperties;
import com.dalai.llama.product.service.didww.DidwwProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DidwwSyncScheduler {

    private final DidwwProperties properties;
    private final DidRepository didRepository;
    private final DidwwProvisioningService didwwService;

    @Scheduled(fixedDelayString = "#{${didww.sync-interval-minutes:60} * 60 * 1000}")
    public void syncDidwwState() {
        if (!properties.isSyncEnabled()) {
            log.debug("DIDWW sync is disabled");
            return;
        }

        log.info("Running DIDWW sync job");

        try {
            // 1. Find DIDs stuck in PROVISIONING state for too long
            List<Did> stuckDids = didRepository.findByStatus(DidStatus.PROVISIONING);
            for (Did did : stuckDids) {
                if (did.getCreatedAt().isBefore(Instant.now().minusSeconds(600))) {
                    log.warn("DID {} stuck in PROVISIONING for >10 minutes, marking as PENDING", did.getNumber());
                    did.setStatus(DidStatus.PENDING);
                    did.setUpdatedAt(Instant.now());
                    didRepository.save(did);
                }
            }

            // 2. Retry PENDING DIDs
            List<Did> pendingDids = didRepository.findByStatus(DidStatus.PENDING);
            log.info("Found {} pending DIDs to retry", pendingDids.size());

            for (Did did : pendingDids) {
                try {
                    retryProvisioning(did);
                } catch (Exception e) {
                    log.error("Failed to retry provisioning for DID {}: {}", did.getNumber(), e.getMessage());
                }
            }

            // 3. TODO: Fetch active DIDs from DIDWW API and compare with local DB
            // This would require DIDWW to have a list DIDs endpoint
            // var remoteDids = didwwService.listActiveDids();
            // Compare and repair drift

            log.info("DIDWW sync job completed");

        } catch (Exception e) {
            log.error("DIDWW sync job failed: {}", e.getMessage(), e);
        }
    }

    private void retryProvisioning(Did did) {
        log.info("Retrying provisioning for DID: {}", did.getNumber());

        did.setStatus(DidStatus.PROVISIONING);
        did.setUpdatedAt(Instant.now());
        didRepository.save(did);

        try {
            // Call DIDWW to check/complete the order
            String orderId = didwwService.orderDid(did.getNumber());
            did.setDidwwDidId(orderId);
            did.setStatus(DidStatus.ACTIVE);
            did.setProvisionedAt(Instant.now());
            did.setUpdatedAt(Instant.now());
            didRepository.save(did);

            log.info("Successfully provisioned DID: {}", did.getNumber());

        } catch (Exception e) {
            log.error("Retry failed for DID {}: {}", did.getNumber(), e.getMessage());
            did.setStatus(DidStatus.PENDING);
            did.setUpdatedAt(Instant.now());
            didRepository.save(did);
        }
    }
}