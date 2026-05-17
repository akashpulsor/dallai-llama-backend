package com.dalai.llama.tenant.domain.event;

import com.dalai.llama.tenant.domain.entity.enums.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record CredentialDeliveryEvent(
        UUID deliveryId,
        UUID tenantId,
        UUID tenantUserId,
        String keycloakUserId,
        DeliveryStatus previousStatus,
        DeliveryStatus newStatus,
        String actorSubject,
        Instant occurredAt
) {}
