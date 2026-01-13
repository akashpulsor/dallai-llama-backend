package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.SipProvider;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import com.dalai.llama.product.repository.SipTrunkRepository;
import com.dalai.llama.product.service.SipTrunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SipTrunkServiceImpl implements SipTrunkService {

    private final SipTrunkRepository sipTrunkRepository;

    @Override
    public SipTrunk createTrunk(UUID tenantId, SipTrunk trunk) {
        trunk.setId(UUID.randomUUID());
        trunk.setTenantId(tenantId);
        trunk.setProvider(SipProvider.CUSTOM);
        trunk.setStatus(SipTrunkStatus.PENDING);
        trunk.setHealthy(false);
        trunk.setMaxConcurrentCalls(100);
        trunk.setMaxCallsPerSecond(10);
        trunk.setCreatedAt(Instant.now());
        trunk.setUpdatedAt(Instant.now());

        trunk = sipTrunkRepository.save(trunk);
        log.info("Created SIP trunk for tenant {}: {}", tenantId, trunk.getName());

        return trunk;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SipTrunk> getTrunks(UUID tenantId) {
        return sipTrunkRepository.findByTenantId(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public SipTrunk getTrunk(UUID tenantId, UUID trunkId) {
        return sipTrunkRepository.findById(trunkId)
                .filter(t -> tenantId.equals(t.getTenantId()))
                .orElseThrow(() -> new RuntimeException("SIP trunk not found: " + trunkId));
    }

    @Override
    public void deleteTrunk(UUID tenantId, UUID trunkId) {
        SipTrunk trunk = getTrunk(tenantId, trunkId);
        sipTrunkRepository.delete(trunk);
        log.info("Deleted SIP trunk for tenant {}: {}", tenantId, trunkId);
    }

    public SipTrunk getOrCreatePlatformTrunk() {
        return sipTrunkRepository.findByTenantId(null).stream()
                .filter(t -> t.getProvider() == SipProvider.DIDWW)
                .findFirst()
                .orElseGet(this::createPlatformTrunk);
    }

    private SipTrunk createPlatformTrunk() {
        SipTrunk trunk = SipTrunk.builder()
                .id(UUID.randomUUID())
                .tenantId(null)
                .name("Platform DIDWW Trunk")
                .provider(SipProvider.DIDWW)
                .server("sip.didww.com")
                .port(5060)
                .status(SipTrunkStatus.ACTIVE)
                .isHealthy(true)
                .maxConcurrentCalls(1000)
                .maxCallsPerSecond(50)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return sipTrunkRepository.save(trunk);
    }
}