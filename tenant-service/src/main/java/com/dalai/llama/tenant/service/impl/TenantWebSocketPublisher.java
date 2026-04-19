package com.dalai.llama.tenant.service.impl;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantWebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Generic tenant scoped publish
     */
    public void publish(UUID tenantId, String channel, Object payload) {
        String destination =
                "/topic/tenant/" + tenantId + "/" + channel;

        log.info("Publishing WS event to {}", destination);

        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * Provisioning updates
     */
    public void publishProvisioningEvent(
            UUID tenantId,
            String status,
            String message
    ) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "PROVISIONING_UPDATE");
        event.put("tenant_id", tenantId);
        event.put("status", status);
        event.put("message", message);

        publish(tenantId, "provisioning", event);
    }

    /**
     * App lifecycle events
     */
    public void publishAppEvent(
            UUID tenantId,
            String appId,
            String status
    ) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "APP_STATUS_CHANGED");
        event.put("tenant_id", tenantId);
        event.put("app_id", appId);
        event.put("status", status);

        publish(tenantId, "apps", event);
    }

    /**
     * Billing events
     */
    public void publishBillingEvent(
            UUID tenantId,
            String invoiceId,
            String status
    ) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "BILLING_UPDATE");
        event.put("tenant_id", tenantId);
        event.put("invoice_id", invoiceId);
        event.put("status", status);

        publish(tenantId, "billing", event);
    }
}