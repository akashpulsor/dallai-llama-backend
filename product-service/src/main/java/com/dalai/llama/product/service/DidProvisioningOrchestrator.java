package com.dalai.llama.product.service;

import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;

import java.util.List;

public interface DidProvisioningOrchestrator {

    List<AvailableDidResponse> search(SearchAvailableDidsRequest request);

    DidProvisioningService resolveProvider(String country);
}
