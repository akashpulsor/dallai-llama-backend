package com.dalai.llama.tenant.controller;


import com.dalai.llama.tenant.service.impl.TenantWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class TenantWebSocketController {

    private final TenantWebSocketPublisher publisher;

    /**
     * Client sends ping
     * /app/tenant/{tenantId}/ping
     */
    @MessageMapping("/tenant/{tenantId}/ping")
    public void ping(
            @DestinationVariable UUID tenantId,
            Principal principal
    ) {
        log.info(
                "Ping received from tenant={} user={}",
                tenantId,
                principal != null ? principal.getName() : "anonymous"
        );

        Map<String, Object> response = new HashMap<>();
        response.put("event", "PING_ACK");
        response.put("tenant_id", tenantId);
        response.put("message", "connection active");

        publisher.publish(
                tenantId,
                "notifications",
                response
        );
    }

    /**
     * Manual refresh event
     * /app/tenant/{tenantId}/refresh
     */
    @MessageMapping("/tenant/{tenantId}/refresh")
    public void refresh(
            @DestinationVariable UUID tenantId,
            Principal principal
    ) {
        log.info(
                "Refresh requested tenant={} user={}",
                tenantId,
                principal != null ? principal.getName() : "anonymous"
        );

        publisher.publishProvisioningEvent(
                tenantId,
                "REFRESH",
                "Tenant data refresh triggered"
        );
    }
}