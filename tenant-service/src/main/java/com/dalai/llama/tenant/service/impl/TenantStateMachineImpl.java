package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantStateAudit;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.TenantActivatedEvent;
import com.dalai.llama.tenant.domain.event.TenantSuspendedEvent;
import com.dalai.llama.tenant.domain.exception.InvalidStateTransitionException;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.repository.TenantStateAuditRepository;
import com.dalai.llama.tenant.service.TenantStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantStateMachineImpl implements TenantStateMachine {

    private final TenantRepository tenantRepository;
    private final TenantStateAuditRepository auditRepository;
    private final TenantEventProducer eventProducer;

    private static final Map<TenantStatus, Set<TenantStatus>> TRANSITIONS = Map.ofEntries(
            // Phase 1: Business Setup & Compliance
            Map.entry(TenantStatus.CREATED, Set.of(TenantStatus.IDENTITY_CREATED)),
            Map.entry(TenantStatus.IDENTITY_CREATED, Set.of(TenantStatus.WALLET_CREATED,TenantStatus.ERROR)),
            Map.entry(TenantStatus.WALLET_CREATED, Set.of(TenantStatus.WALLET_DELETED,TenantStatus.ACTIVE,TenantStatus.ERROR)),
            Map.entry(TenantStatus.WALLET_DELETED, Set.of(TenantStatus.IDENTITY_DELETED,TenantStatus.ERROR)),
            Map.entry(TenantStatus.IDENTITY_DELETED, Set.of(TenantStatus.INACTIVE,TenantStatus.ERROR)),
            Map.entry(TenantStatus.DELETED, Set.of(TenantStatus.ACTIVE,TenantStatus.ERROR)),
            // Operational Lifecycle & Error Recovery
            Map.entry(TenantStatus.ACTIVE, Set.of(TenantStatus.SUSPENDED, TenantStatus.IDENTITY_CREATED, TenantStatus.DELETED, TenantStatus.KYC_REQUIRED,TenantStatus.ERROR)),
            Map.entry(TenantStatus.SUSPENDED, Set.of(TenantStatus.ACTIVE, TenantStatus.DELETED,TenantStatus.ERROR)),
            Map.entry(TenantStatus.ERROR, Set.of(TenantStatus.PROVISIONING_RESTART, TenantStatus.PROVISIONING, TenantStatus.DELETED,TenantStatus.ERROR))
    );

    @Override
    @Transactional
    public void transition(Tenant tenant, TenantStatus target, String triggerSource, String message) {
        TenantStatus current = tenant.getStatus();

        Set<TenantStatus> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            log.warn("Invalid transition: {} -> {} for tenant {}", current, target, tenant.getId());
            throw new InvalidStateTransitionException(String.format("Cannot transition from %s to %s", current, target));
        }

        log.info("Tenant {} transitioning: {} -> {} (trigger: {})", tenant.getId(), current, target, triggerSource);

        TenantStateAudit audit = new TenantStateAudit();
        audit.setTenant(tenant);
        audit.setOldStatus(current.name());
        audit.setNewStatus(target.name());
        audit.setTriggerSource(triggerSource);
        audit.setMessage(message);
        auditRepository.save(audit);

        tenant.setStatus(target);
        tenant.setStatusMessage(message);
        tenant.setStatusChangedAt(OffsetDateTime.now());

        switch (target) {
            case ACTIVE -> {
                tenant.setActivatedAt(OffsetDateTime.now());
                tenant.setSuspendedAt(null);
                tenantRepository.save(tenant);
                eventProducer.publish("tenant.activated", tenant.getId().toString(), new TenantActivatedEvent(tenant.getId()));
            }
            case PROVISIONING_RESTART -> {
                // Keep pre-requisite data (DID/SIP) but flag for re-deployment
                tenant.setStatusMessage("Restarting provisioning: " + message);
                tenantRepository.save(tenant);
            }
            case DELETED -> {
                tenant.setDeletedAt(OffsetDateTime.now());
                tenantRepository.save(tenant);
            }
            default -> tenantRepository.save(tenant);
        }

        eventProducer.publish("tenant.state.changed", tenant.getId().toString(),
                Map.of("tenantId", tenant.getId(), "oldState", current.name(), "newState", target.name(), "message", message != null ? message : ""));
    }
}