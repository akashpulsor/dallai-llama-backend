package com.dalai.llama.tenant.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantStateChangedEvent {
    private UUID tenantId;
    private String oldState;
    private String newState;
    private String message;
}