package com.dalai.llama.product.service;


import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.SipEndpoint;

import java.util.UUID;

public interface SipEndpointService {

    SipEndpoint createEndpoint(Did did);

    SipEndpoint getByDidId(UUID didId);

    SipEndpoint getById(UUID id);

    void delete(UUID id);
}
