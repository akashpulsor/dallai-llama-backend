package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantStateAudit;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.TenantActivatedEvent;
import com.dalai.llama.tenant.domain.event.TenantStateChangedEvent;
import com.dalai.llama.tenant.domain.exception.InvalidStateTransitionException;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.repository.TenantStateAuditRepository;
import com.dalai.llama.tenant.service.TenantStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
            Map.entry(TenantStatus.CREATED, Set.of(TenantStatus.IDENTITY_CREATED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.IDENTITY_CREATED, Set.of(TenantStatus.WALLET_CREATED, TenantStatus.CREATED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.WALLET_CREATED, Set.of(TenantStatus.PROVISIONING, TenantStatus.PROVISIONING_FAILED, TenantStatus.IDENTITY_CREATED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.PROVISIONING, Set.of(TenantStatus.ACTIVE, TenantStatus.PROVISIONING_FAILED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.PROVISIONING_FAILED, Set.of(TenantStatus.PROVISIONING, TenantStatus.DELETED)),
            Map.entry(TenantStatus.ACTIVE, Set.of(TenantStatus.PROVISIONING, TenantStatus.SUSPENDED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.SUSPENDED, Set.of(TenantStatus.ACTIVE, TenantStatus.DELETED))
    );

    /**
     * Lenient transition: returns true on success, false on invalid transition (no exception).
     * Use this from non-critical call sites (e.g. orchestrators that should log-and-continue
     * regardless of whether the transition succeeded).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryTransition(Tenant tenant, TenantStatus target, String triggerSource, String message) {
        TenantStatus current = tenant.getStatus();
        Set<TenantStatus> allowed = TRANSITIONS.getOrDefault(current, Set.of());

        if (!allowed.contains(target)) {
            log.warn("Skipping invalid transition: {} -> {} for tenant {} (trigger: {})",
                    current, target, tenant.getId(), triggerSource);
            return false;
        }

        applyTransition(tenant, current, target, triggerSource, message);
        return true;
    }

    /**
     * Strict transition: throws InvalidStateTransitionException on invalid transition.
     * Use this from REST APIs / call sites that genuinely need to reject invalid input.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transition(Tenant tenant, TenantStatus target, String triggerSource, String message) {
        TenantStatus current = tenant.getStatus();
        Set<TenantStatus> allowed = TRANSITIONS.getOrDefault(current, Set.of());

        if (!allowed.contains(target)) {
            log.warn("Invalid transition: {} -> {} for tenant {}", current, target, tenant.getId());
            throw new InvalidStateTransitionException(
                    String.format("Cannot transition from %s to %s", current, target));
        }

        applyTransition(tenant, current, target, triggerSource, message);
    }

    /**
     * The actual state change + audit + event publishing.
     * Private — only called once invariants are validated.
     */
    private void applyTransition(Tenant tenant, TenantStatus current, TenantStatus target,
                                 String triggerSource, String message) {
        log.info("Tenant {} transitioning: {} -> {} (trigger: {})",
                tenant.getId(), current, target, triggerSource);

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
                eventProducer.publishTenantActivated(tenant.getId().toString(),
                        new TenantActivatedEvent(tenant.getId()));
            }
            case SUSPENDED -> {
                tenant.setSuspendedAt(OffsetDateTime.now());
                tenantRepository.save(tenant);
            }
            case DELETED -> {
                tenant.setDeletedAt(OffsetDateTime.now());
                tenantRepository.save(tenant);
            }
            default -> tenantRepository.save(tenant);
        }

        eventProducer.publishTenantStateChanged(tenant.getId().toString(),
                TenantStateChangedEvent.builder()
                        .tenantId(tenant.getId())
                        .oldState(current.name())
                        .newState(target.name())
                        .message(message != null ? message : "")
                        .build());
    }
}