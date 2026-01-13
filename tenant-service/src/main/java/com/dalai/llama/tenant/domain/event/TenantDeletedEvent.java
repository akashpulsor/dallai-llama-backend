package com.dalai.llama.tenant.domain.event;

import java.util.UUID;

public record TenantDeletedEvent(UUID tenantId)  {
}
