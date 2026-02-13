package com.dalai.llama.product.service;

import com.dalai.llama.product.dto.request.ProvisionDidRequest;
import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;

import java.util.List;
import java.util.UUID;

public interface DidProvisioningService {

    /**
     * Search available DIDs from provider
     */
    List<AvailableDidResponse> searchAvailableDids(SearchAvailableDidsRequest request);

    /**
     * Order / provision DID at provider level
     * (does NOT save in DB — only external provider call)
     */
    String orderDid(String number);

    /**
     * Configure SIP endpoint at provider
     */
    String configureSip(String name, String host, int port);

    /**
     * Create inbound trunk at provider
     */
    String createTrunk(String name, String sipConfigId);

    /**
     * Check if provider supports this country
     */
    boolean supports(String country);

    /**
     * Provider name (DIDWW / EPSILON / etc.)
     */
    String providerName();

    boolean isHealthy();
}
