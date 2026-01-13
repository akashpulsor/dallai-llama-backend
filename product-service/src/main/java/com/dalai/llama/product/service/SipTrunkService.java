package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.SipTrunk;

import java.util.List;
import java.util.UUID;

public interface SipTrunkService {

    SipTrunk createTrunk(UUID tenantId, SipTrunk trunk);

    List<SipTrunk> getTrunks(UUID tenantId);

    SipTrunk getTrunk(UUID tenantId, UUID trunkId);

    void deleteTrunk(UUID tenantId, UUID trunkId);
}
