package com.dalai.llama.tenant.domain.event;

import java.util.UUID;

public record  TenantCreatedEvent (UUID tenantId, String slug) {}