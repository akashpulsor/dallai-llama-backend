package com.dalai.llama.product.service.epsilon;

import com.dalai.llama.product.domain.exception.EpsilonApiException;
import com.dalai.llama.product.service.ProviderApiService;
import com.dalai.llama.product.service.ProviderHealthIndicator;
import com.dalai.llama.product.service.epsilon.dto.DidListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.util.retry.Retry;

import java.time.Duration;


@Service
@RequiredArgsConstructor
public class EpsilonApiService implements ProviderApiService, ProviderHealthIndicator {

    private final EpsilonClient client;

    private Retry retrySpec() {
        return Retry.fixedDelay(3, Duration.ofSeconds(2));
    }

    public DidListResponse getDidList() {
        return client.getDidList()
                .retryWhen(retrySpec())
                .onErrorMap(e -> new EpsilonApiException("Epsilon DID list fetch failed", e))
                .block();
    }

    public String orderDid(String number) {
        return client.orderDid(number)
                .retryWhen(retrySpec())
                .onErrorMap(e -> new EpsilonApiException("Epsilon order failed", e))
                .block();
    }

    @Override
    public boolean isHealthy() {
        try {
            //getDidList();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String providerName() {
        return "EPSILON";
    }
}
