package com.dalai.llama.tenant.kafka.producer;

import com.dalai.llama.tenant.domain.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
        kafkaTemplate.send(TENANT_CREATED_TOPIC, event.getTenantId().toString(), event);
        log.info("Published tenant created: tenantId={} slug={}", event.getTenantId(), event.getSlug());
    }

    public void publishTenantDeleted(TenantDeletedEvent event) {
        kafkaTemplate.send(TENANT_DELETED_TOPIC, event.getTenantId().toString(), event);
        log.info("Published tenant deleted: tenantId={}", event.getTenantId());
    }

    public void publishTenantActivated(String key, TenantActivatedEvent event) {
        kafkaTemplate.send(TENANT_ACTIVATED_TOPIC, key, event);
        log.info("Published tenant activated: tenantId={}", key);
    }

    public void publishTenantStateChanged(String key, TenantStateChangedEvent event) {
        kafkaTemplate.send(TENANT_STATE_CHANGED_TOPIC, key, event);
        log.info("Published tenant state changed: tenantId={} {} -> {}",
                key, event.getOldState(), event.getNewState());
    }

    public void publishProvisioningCompleted(ProvisioningCompletedEvent event) {
        kafkaTemplate.send(PROVISIONING_COMPLETED_TOPIC, event.getTenantId().toString(), event);
        log.info("Published provisioning {}: tenantApp={} tenant={}",
                event.getStatus(), event.getTenantAppId(), event.getTenantId());
    }
}