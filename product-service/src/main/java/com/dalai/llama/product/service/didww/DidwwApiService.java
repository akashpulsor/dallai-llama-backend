package com.dalai.llama.product.service.didww;


import com.dalai.llama.product.domain.exception.DidwwApiException;
import com.dalai.llama.product.service.ProviderApiService;
import com.dalai.llama.product.service.ProviderHealthIndicator;
import com.dalai.llama.product.service.didww.dto.DidwwAvailableDidResponse;
import com.dalai.llama.product.service.didww.dto.DidwwOrderRequest;
import com.dalai.llama.product.service.didww.dto.DidwwSipConfigRequest;
import com.dalai.llama.product.service.didww.dto.DidwwTrunkRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class DidwwApiService implements ProviderApiService, ProviderHealthIndicator {

    private final DidwwClient client;

    private Retry retrySpec() {
        return Retry.fixedDelay(3, Duration.ofSeconds(2));
    }

    public DidwwAvailableDidResponse searchAvailableDids(String query) {
        return client.searchAvailableDids(query)
                .retryWhen(retrySpec())
                .onErrorMap(e -> new DidwwApiException("DID search failed", e))
                .block();
    }

    public String orderDid(DidwwOrderRequest request) {
        return client.orderDid(request)
                .retryWhen(retrySpec())
                .onErrorMap(e -> new DidwwApiException("DID order failed", e))
                .block();
    }

    public String createSipConfig(DidwwSipConfigRequest request) {
        return client.createSipConfig(request)
                .retryWhen(retrySpec())
                .onErrorMap(e -> new DidwwApiException("SIP config creation failed", e))
                .block();
    }

    public String createTrunk(DidwwTrunkRequest request) {
        return client.createTrunk(request)
                .retryWhen(retrySpec())
                .onErrorMap(e -> new DidwwApiException("Trunk creation failed", e))
                .block();
    }

    @Override
    public boolean isHealthy() {
        try {
            searchAvailableDids("limit=1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String providerName() {
        return "DIDWW";
    }
}
