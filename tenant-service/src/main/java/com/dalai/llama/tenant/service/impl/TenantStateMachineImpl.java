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

    // Define valid state transitions
    private static final Map<TenantStatus, Set<TenantStatus>> TRANSITIONS = Map.ofEntries(
            // Phase 1: Business Setup
            Map.entry(TenantStatus.CREATED, Set.of(
                    TenantStatus.PLAN_ASSIGNED,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.PLAN_ASSIGNED, Set.of(
                    TenantStatus.PRODUCTS_CONFIGURED,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.PRODUCTS_CONFIGURED, Set.of(
                    TenantStatus.BILLING_READY,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.BILLING_READY, Set.of(
                    TenantStatus.READY_TO_PROVISION,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.READY_TO_PROVISION, Set.of(
                    TenantStatus.PROVISIONING,
                    TenantStatus.DELETED
            )),

            // Phase 2: Provisioning substates
            Map.entry(TenantStatus.PROVISIONING, Set.of(
                    TenantStatus.PROVISIONING_KEYCLOAK,
                    TenantStatus.ERROR,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.PROVISIONING_KEYCLOAK, Set.of(
                    TenantStatus.PROVISIONING_NAMESPACE,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_NAMESPACE, Set.of(
                    TenantStatus.PROVISIONING_INFRA,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_INFRA, Set.of(
                    TenantStatus.PROVISIONING_TELECOM,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_TELECOM, Set.of(
                    TenantStatus.PROVISIONING_MEDIA_SERVER,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_MEDIA_SERVER, Set.of(
                    TenantStatus.PROVISIONING_SERVICES,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_SERVICES, Set.of(
                    TenantStatus.PROVISIONING_LOADBALANCER,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_LOADBALANCER, Set.of(
                    TenantStatus.PROVISIONING_WAITING_IP,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_WAITING_IP, Set.of(
                    TenantStatus.PROVISIONING_DIDWW,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_DIDWW, Set.of(
                    TenantStatus.PROVISIONING_DASHBOARD,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_DASHBOARD, Set.of(
                    TenantStatus.PROVISIONING_USERS,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.PROVISIONING_USERS, Set.of(
                    TenantStatus.HEALTH_CHECK,
                    TenantStatus.ERROR
            )),
            Map.entry(TenantStatus.HEALTH_CHECK, Set.of(
                    TenantStatus.ACTIVE,
                    TenantStatus.ERROR
            )),

            // Final states
            Map.entry(TenantStatus.ACTIVE, Set.of(
                    TenantStatus.SUSPENDED,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.SUSPENDED, Set.of(
                    TenantStatus.ACTIVE,
                    TenantStatus.DELETED
            )),
            Map.entry(TenantStatus.ERROR, Set.of(
                    TenantStatus.PROVISIONING,
                    TenantStatus.DELETED
            ))
    );

    @Override
    @Transactional
    public void transition(Tenant tenant, TenantStatus target, String triggerSource, String message) {
        TenantStatus current = tenant.getStatus();

        // Validate transition
        Set<TenantStatus> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            log.warn("Invalid transition attempt: {} -> {} for tenant {}",
                    current, target, tenant.getId());
            throw new InvalidStateTransitionException(
                    String.format("Cannot transition from %s to %s", current, target)
            );
        }

        log.info("Tenant {} transitioning: {} -> {} (trigger: {})",
                tenant.getId(), current, target, triggerSource);

        // Create audit record
        TenantStateAudit audit = new TenantStateAudit();
        audit.setTenant(tenant);
        audit.setOldStatus(current.name());
        audit.setNewStatus(target.name());
        audit.setOldSubstatus(tenant.getSubstatus());
        audit.setTriggerSource(triggerSource);
        audit.setMessage(message);
        auditRepository.save(audit);

        // Update tenant state
        tenant.setStatus(target);
        tenant.setStatusMessage(message);
        tenant.setStatusChangedAt(OffsetDateTime.now());

        // Handle specific status changes
        switch (target) {
            case ACTIVE -> {
                tenant.setActivatedAt(OffsetDateTime.now());
                tenant.setSuspendedAt(null);
                tenant.setSuspensionReason(null);
                tenantRepository.save(tenant);
                eventProducer.publish("tenant.activated", tenant.getId().toString(),
                        new TenantActivatedEvent(tenant.getId()));
            }
            case SUSPENDED -> {
                tenant.setSuspendedAt(OffsetDateTime.now());
                tenant.setSuspensionReason(message);
                tenantRepository.save(tenant);
                eventProducer.publish("tenant.suspended", tenant.getId().toString(),
                        new TenantSuspendedEvent(tenant.getId(), message));
            }
            case DELETED -> {
                tenant.setDeletedAt(OffsetDateTime.now());
                tenantRepository.save(tenant);
            }
            default -> tenantRepository.save(tenant);
        }

        // Publish state change event
        eventProducer.publish("tenant.state.changed", tenant.getId().toString(),
                Map.of(
                        "tenantId", tenant.getId(),
                        "oldState", current.name(),
                        "newState", target.name(),
                        "message", message != null ? message : ""
                ));
    }
}