package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.Did;

import java.util.List;
import java.util.UUID;

public interface DidService {

    Did provisionDid(UUID tenantId, String number, UUID sipTrunkId);

    List<Did> getTenantDids(UUID tenantId);

    Did getDid(UUID tenantId, UUID didId);

    void releaseDid(UUID tenantId, UUID didId);
}
