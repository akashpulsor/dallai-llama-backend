package com.dalai.llama.product.service;


public interface ProviderHealthIndicator {

    boolean isHealthy();

    String providerName();
}
