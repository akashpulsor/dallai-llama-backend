package com.dalai.llama.tenant.service;


import com.dalai.llama.tenant.dto.response.ReadinessCheckResponse;

import java.util.UUID;

public interface ReadinessCheckService {

    ReadinessCheckResponse check(UUID tenantId);

    void assertReady(UUID tenantId);
}
