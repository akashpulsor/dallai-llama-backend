package com.dalai.llama.product.service.didww;


import com.dalai.llama.product.service.didww.dto.DidwwAvailableDidResponse;
import com.dalai.llama.product.service.didww.dto.DidwwOrderRequest;
import com.dalai.llama.product.service.didww.dto.DidwwSipConfigRequest;
import com.dalai.llama.product.service.didww.dto.DidwwTrunkRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class DidwwClient {

    private final WebClient.Builder webClientBuilder;
    private final DidwwProperties properties;

    private WebClient client() {
        return webClientBuilder
                .baseUrl(properties.getApiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    public Mono<DidwwAvailableDidResponse> searchAvailableDids(String query) {
        return client()
                .get()
                .uri(uri -> uri.path("/available_dids").query(query).build())
                .retrieve()
                .bodyToMono(DidwwAvailableDidResponse.class);
    }

    public Mono<String> orderDid(DidwwOrderRequest request) {
        return client()
                .post()
                .uri("/orders")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class);
    }

    public Mono<String> createSipConfig(DidwwSipConfigRequest request) {
        return client()
                .post()
                .uri("/sip_configs")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class);
    }

    public Mono<String> createTrunk(DidwwTrunkRequest request) {
        return client()
                .post()
                .uri("/voice_in_trunks")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class);
    }
}
