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
            Map.entry(TenantStatus.CREATED, Set.of(TenantStatus.PRODUCTS_CONFIGURED, TenantStatus.DELETED, TenantStatus.PROVISIONING_RESTART)),
            Map.entry(TenantStatus.PLAN_ASSIGNED, Set.of(TenantStatus.PROVISIONING_KEYCLOAK,TenantStatus.DID_PURCHASED, TenantStatus.KYC_SUBMITTED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.DID_PURCHASED, Set.of(TenantStatus.SIP_CONFIGURED, TenantStatus.KYC_SUBMITTED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.SIP_CONFIGURED, Set.of(TenantStatus.BILLING_READY, TenantStatus.KYC_SUBMITTED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.KYC_SUBMITTED, Set.of(TenantStatus.KYC_APPROVED, TenantStatus.KYC_REJECTED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.KYC_REJECTED, Set.of(TenantStatus.KYC_SUBMITTED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.KYC_APPROVED, Set.of(TenantStatus.PRODUCTS_CONFIGURED, TenantStatus.BILLING_READY, TenantStatus.DELETED)),
            Map.entry(TenantStatus.PRODUCTS_CONFIGURED, Set.of(TenantStatus.PLAN_ASSIGNED, TenantStatus.DELETED)),
            Map.entry(TenantStatus.BILLING_READY, Set.of(TenantStatus.PROVISIONING_KEYCLOAK, TenantStatus.DELETED)),

            // Phase 2: Provisioning Entry & Restart Logic
            Map.entry(TenantStatus.READY_TO_PROVISION, Set.of(TenantStatus.PROVISIONING, TenantStatus.DELETED)),
            Map.entry(TenantStatus.PROVISIONING_RESTART, Set.of(TenantStatus.PROVISIONING, TenantStatus.DELETED)),

            // Phase 3: Technical Provisioning Pipeline (Added RESTART to all states to prevent transition errors)
            Map.entry(TenantStatus.PROVISIONING, Set.of( TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR, TenantStatus.DELETED)),
            Map.entry(TenantStatus.PROVISIONING_KEYCLOAK, Set.of(TenantStatus.READY_TO_PROVISION, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_NAMESPACE, Set.of(TenantStatus.PROVISIONING_INFRA, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_INFRA, Set.of(TenantStatus.PROVISIONING_TELECOM, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_TELECOM, Set.of(TenantStatus.PROVISIONING_MEDIA_SERVER, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_MEDIA_SERVER, Set.of(TenantStatus.PROVISIONING_SERVICES, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_SERVICES, Set.of(TenantStatus.PROVISIONING_LOADBALANCER, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_LOADBALANCER, Set.of(TenantStatus.PROVISIONING_WAITING_IP, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_WAITING_IP, Set.of(TenantStatus.PROVISIONING_DIDWW, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_DIDWW, Set.of(TenantStatus.PROVISIONING_DASHBOARD, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_DASHBOARD, Set.of(TenantStatus.PROVISIONING_USERS, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.PROVISIONING_USERS, Set.of(TenantStatus.HEALTH_CHECK, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),
            Map.entry(TenantStatus.HEALTH_CHECK, Set.of(TenantStatus.ACTIVE, TenantStatus.PROVISIONING_RESTART, TenantStatus.ERROR)),

            // Operational Lifecycle & Error Recovery
            Map.entry(TenantStatus.ACTIVE, Set.of(TenantStatus.SUSPENDED, TenantStatus.DELETED, TenantStatus.PROVISIONING_RESTART)),
            Map.entry(TenantStatus.SUSPENDED, Set.of(TenantStatus.ACTIVE, TenantStatus.DELETED)),
            Map.entry(TenantStatus.ERROR, Set.of(TenantStatus.PROVISIONING_RESTART, TenantStatus.PROVISIONING, TenantStatus.DELETED))
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