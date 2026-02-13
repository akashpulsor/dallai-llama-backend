package com.dalai.llama.product.service.epsilon;


import com.dalai.llama.product.service.epsilon.dto.DidListResponse;
import com.dalai.llama.product.service.epsilon.dto.EpsilonVoiceProperties;
import com.dalai.llama.product.service.epsilon.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class EpsilonClient {

    private final WebClient.Builder webClientBuilder;

    private final EpsilonVoiceProperties properties;


    private final AtomicReference<String> tokenRef = new AtomicReference<>();

    private WebClient baseClient() {
        return webClientBuilder
                .baseUrl(properties.getApiUrl())
                .build();
    }

    private WebClient authClient(String token) {
        return webClientBuilder
                .baseUrl(properties.getApiUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    /**
     * Fetch DID list
     */
    public Mono<DidListResponse> getDidList() {

        return ensureLoggedIn()
                .flatMap(token ->
                        authClient(token)
                                .get()
                                .uri("/api/get-did-list")
                                .retrieve()
                                .onStatus(HttpStatus.UNAUTHORIZED::equals, response -> {
                                    log.warn("Epsilon token expired. Clearing token.");
                                    tokenRef.set(null);
                                    return Mono.error(new RuntimeException("Unauthorized"));
                                })
                                .bodyToMono(DidListResponse.class)
                )
                .doOnNext(response ->
                        log.info("Epsilon DID List Response: {}", response)
                )
                .retry(1); // Retry once after token refresh
    }

    /**
     * Optional: If Epsilon later supports order
     */
    public Mono<String> orderDid(String number) {
        return ensureLoggedIn()
                .flatMap(token ->
                        authClient(token)
                                .post()
                                .uri("/api/order-did")
                                .bodyValue(number)
                                .retrieve()
                                .onStatus(HttpStatus.UNAUTHORIZED::equals, response -> {
                                    tokenRef.set(null);
                                    return Mono.error(new RuntimeException("Unauthorized"));
                                })
                                .bodyToMono(String.class)
                )
                .retry(1);
    }

    /**
     * Ensure token exists
     */
    private Mono<String> ensureLoggedIn() {
        String token = tokenRef.get();
        if (token != null) {
            return Mono.just(token);
        }
        return login();
    }

    /**
     * Login and cache token
     */
    private Mono<String> login() {

        log.info("Logging into Epsilon...");

        return baseClient()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/login")
                        .queryParam("username", properties.getUsername())
                        .queryParam("password", properties.getPassword())
                        .build())
                .retrieve()
                .bodyToMono(LoginResponse.class)
                .map(response -> {

                    if (response == null || response.getToken() == null) {
                        throw new RuntimeException("Epsilon login failed");
                    }

                    tokenRef.set(response.getToken());
                    log.info("Epsilon login successful");

                    return response.getToken();
                });
    }
}
