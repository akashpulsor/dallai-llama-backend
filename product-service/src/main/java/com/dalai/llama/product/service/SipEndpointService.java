package com.dalai.llama.product.service;


import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.SipEndpoint;

public interface SipEndpointService {

    SipEndpoint createEndpoint(Did did);
}
