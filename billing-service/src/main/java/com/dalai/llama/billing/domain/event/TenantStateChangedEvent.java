package com.dalai.llama.billing.domain.event;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantStateChangedEvent(
        UUID tenantId,
        String oldState,
        String newState,
        String message
) {}