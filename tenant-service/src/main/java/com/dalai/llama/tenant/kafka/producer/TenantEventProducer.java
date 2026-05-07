package com.dalai.llama.tenant.kafka.producer;

import com.dalai.llama.tenant.domain.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public static final String TENANT_ACTIVATED_TOPIC = "tenant.activated";
    public static final String TENANT_STATE_CHANGED_TOPIC = "tenant.state.changed";
    public static final String PROVISIONING_COMPLETED_TOPIC = "tenant.provisioning.completed";
    public static final String TENANT_CREATED_TOPIC = "tenant.created";
    public static final String TENANT_DELETED_TOPIC = "tenant.deleted";

    public void publishTenantCreated(TenantCreatedEvent event) {
        afterCommit(() -> {
            kafkaTemplate.send(TENANT_CREATED_TOPIC, event.getTenantId().toString(), event);
            log.info("Published tenant created: tenantId={} slug={}", event.getTenantId(), event.getSlug());
        });
    }

    public void publishTenantDeleted(TenantDeletedEvent event) {
        afterCommit(() -> {
            kafkaTemplate.send(TENANT_DELETED_TOPIC, event.getTenantId().toString(), event);
            log.info("Published tenant deleted: tenantId={}", event.getTenantId());
        });
    }

    public void publishTenantActivated(String key, TenantActivatedEvent event) {
        afterCommit(() -> {
            kafkaTemplate.send(TENANT_ACTIVATED_TOPIC, key, event);
            log.info("Published tenant activated: tenantId={}", key);
        });
    }

    public void publishTenantStateChanged(String key, TenantStateChangedEvent event) {
        afterCommit(() -> {
            kafkaTemplate.send(TENANT_STATE_CHANGED_TOPIC, key, event);
            log.info("Published tenant state changed: tenantId={} {} -> {}",
                    key, event.getOldState(), event.getNewState());
        });
    }

    public void publishProvisioningCompleted(ProvisioningCompletedEvent event) {
        afterCommit(() -> {
            kafkaTemplate.send(PROVISIONING_COMPLETED_TOPIC, event.getTenantId().toString(), event);
            log.info("Published provisioning {}: tenantApp={} tenant={}",
                    event.getStatus(), event.getTenantAppId(), event.getTenantId());
        });
    }

    /**
     * Run the publish action after the current transaction commits.
     * If there is no active transaction, run it immediately (e.g. Kafka consumer paths,
     * scheduled jobs without @Transactional, or tests).
     */
    private void afterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        publish.run();
                    } catch (Exception e) {
                        log.error("Failed to publish Kafka event after commit", e);
                    }
                }
            });
        } else {
            publish.run();
        }
    }
}