package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.SipEndpoint;
import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.event.DidProvisionedEvent;
import com.dalai.llama.product.domain.event.DidPurchasedEvent;
import com.dalai.llama.product.domain.event.DidReleasedEvent;
import com.dalai.llama.product.domain.exception.BillingBlockedException;
import com.dalai.llama.product.domain.exception.DidNotAvailableException;
import com.dalai.llama.product.domain.exception.DidNotFoundException;
import com.dalai.llama.product.domain.exception.InsufficientBalanceException;
import com.dalai.llama.product.kafka.producer.ProductEventProducer;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.repository.SipTrunkRepository;
import com.dalai.llama.product.service.DidService;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.SipEndpointService;
import com.dalai.llama.product.service.didww.DidwwProvisioningService;
import com.dalai.llama.product.service.didww.dto.DidwwAvailableDidResponse;
import com.dalai.llama.product.util.E164Formatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DidServiceImpl implements DidService {

    private final DidRepository didRepository;
    private final SipTrunkRepository sipTrunkRepository;
    private final EntitlementService entitlementService;
    private final SipEndpointService sipEndpointService;
    private final DidwwProvisioningService didwwService;
    private final ProductEventProducer eventProducer;

    private final BillingServiceClient billingServiceClient;


    // In DidwwProvisioningService - add this method
    /*
    public DidwwAvailableDidResponse.DidInfo getDidInfo(String number) {
        // Search by the exact number
        DidwwAvailableDidResponse response = didwwService.searchAvailableDids("number=" + number);

        return response.getData().stream()
                .filter(d -> d.getNumber().equals(number))
                .findFirst()
                .orElse(null);
    } */

    private BigDecimal getDidMonthlyRate(String number) {
        // Search DIDWW for this specific number to get its rate
        /*
        DidwwAvailableDidResponse response = didwwService.searchAvailableDids(number);

        return response.getData().stream()
                .filter(d -> d.getNumber().equals(number))
                .findFirst()
                .map(d -> new BigDecimal(d.getMonthlyFee()))
                .orElse(getDefaultDidRate()); // fallback

         */
        return new BigDecimal(500.00); // For simplicity, return default rate
    }

    private BigDecimal getDefaultDidRate() {
        return new BigDecimal("500.00"); // Default INR 500/month
    }
    @Override
    public Did provisionDid(UUID tenantId, String number, UUID sipTrunkId) {
        log.info("Provisioning DID {} for tenant {}", number, tenantId);

        // NEW: Check billing state first
        String billingState = billingServiceClient.getBillingState(tenantId);
        if (!"ACTIVE".equals(billingState) && !"GRACE".equals(billingState)) {
            throw new BillingBlockedException("Cannot purchase DID - billing state: " + billingState);
        }

        // NEW: Check sufficient balance for first month rental
        BigDecimal monthlyRate = getDidMonthlyRate(number); // from DIDWW
        if (!billingServiceClient.hasSufficientBalance(tenantId, monthlyRate)) {
            throw new InsufficientBalanceException("Insufficient balance for DID rental");
        }

        // 1. Validate entitlements
        entitlementService.validateDidLimit(tenantId);

        // 2. Normalize number
        String normalizedNumber = E164Formatter.normalize(number);

        // 3. Check if already exists
        didRepository.findByTenantIdAndNumber(tenantId, normalizedNumber)
                .ifPresent(d -> {
                    throw new DidNotAvailableException("DID already provisioned: " + number);
                });

        // 4. Get or use platform trunk
        SipTrunk trunk = sipTrunkId != null
                ? sipTrunkRepository.findById(sipTrunkId).orElse(null)
                : null;

        // 5. Create DID record (status: PENDING)
        Did did = Did.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .sipTrunk(trunk)
                .number(normalizedNumber)
                .displayNumber(E164Formatter.display(normalizedNumber))
                .status(DidStatus.PENDING)
                .currency("INR")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        did = didRepository.save(did);

        // 6. Publish purchased event (for Tenant Service to track)
        eventProducer.publishDidPurchased(DidPurchasedEvent.builder()
                .tenantId(tenantId)
                .didId(did.getId())
                .number(normalizedNumber)
                .occurredAt(Instant.now())
                .build());

        // 7. Order from DIDWW (async in real implementation)
        try {
            did.setStatus(DidStatus.PROVISIONING);
            did.setUpdatedAt(Instant.now());
            didRepository.save(did);

            String didwwOrderId = didwwService.orderDid(normalizedNumber);
            did.setDidwwDidId(didwwOrderId);

            // 8. Create SIP endpoint
            SipEndpoint endpoint = sipEndpointService.createEndpoint(did);

            // 9. Mark as ACTIVE
            did.setStatus(DidStatus.ACTIVE);
            did.setProvisionedAt(Instant.now());
            did.setUpdatedAt(Instant.now());
            did = didRepository.save(did);

            // 10. Publish provisioned event
            eventProducer.publishDidProvisioned(DidProvisionedEvent.builder()
                    .tenantId(tenantId)
                    .didId(did.getId())
                    .number(normalizedNumber)
                    .sipTrunkId(trunk != null ? trunk.getId() : null)
                    .sipEndpointId(endpoint.getId())
                    .provisionedAt(did.getProvisionedAt())
                    .occurredAt(Instant.now())
                    .build());

            log.info("DID {} provisioned successfully for tenant {}", number, tenantId);

        } catch (Exception e) {
            log.error("Failed to provision DID {}: {}", number, e.getMessage());
            did.setStatus(DidStatus.PENDING);
            did.setUpdatedAt(Instant.now());
            didRepository.save(did);
            throw new DidNotAvailableException("Failed to provision DID: " + e.getMessage());
        }

        return did;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Did> getTenantDids(UUID tenantId) {
        return didRepository.findByTenantId(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Did getDid(UUID tenantId, UUID didId) {
        return didRepository.findByTenantIdAndId(tenantId, didId)
                .orElseThrow(() -> new DidNotFoundException(didId.toString()));
    }

    @Override
    public void releaseDid(UUID tenantId, UUID didId) {
        Did did = getDid(tenantId, didId);

        if (did.getStatus() == DidStatus.RELEASED || did.getStatus() == DidStatus.RELEASING) {
            log.info("DID {} already released/releasing, skipping", did.getNumber());
            return;
        }

        log.info("Releasing DID {} for tenant {} (current status: {})", did.getNumber(), tenantId, did.getStatus());

        did.setStatus(DidStatus.RELEASING);
        did.setUpdatedAt(Instant.now());
        didRepository.save(did);

        // TODO: Call DIDWW API to release DID

        did.setStatus(DidStatus.RELEASED);
        did.setReleasedAt(Instant.now());
        did.setUpdatedAt(Instant.now());
        didRepository.save(did);

        eventProducer.publishDidReleased(DidReleasedEvent.builder()
                .tenantId(tenantId)
                .didId(didId)
                .number(did.getNumber())
                .reason("USER_REQUEST")
                .releasedAt(did.getReleasedAt())
                .occurredAt(Instant.now())
                .build());

        log.info("DID {} released for tenant {}", did.getNumber(), tenantId);
    }

    // Internal API for tenant service
    public boolean hasPurchasedDid(UUID tenantId) {
        return didRepository.countByTenantIdAndStatusIn(
                tenantId,
                List.of(DidStatus.ACTIVE, DidStatus.PENDING, DidStatus.PROVISIONING)
        ) > 0;
    }

    public Did getDidByNumber(UUID tenantId, String number) {
        return didRepository.findByTenantIdAndNumber(tenantId, number)
                .orElseThrow(() -> new DidNotFoundException(number));
    }
}