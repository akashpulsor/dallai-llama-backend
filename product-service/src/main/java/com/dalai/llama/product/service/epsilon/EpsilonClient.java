package com.dalai.llama.product.service.epsilon;


import com.dalai.llama.product.service.epsilon.dto.DidListResponse;
import com.dalai.llama.product.service.epsilon.dto.EpsilonVoiceProperties;
import com.dalai.llama.product.service.epsilon.dto.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        // TODO: Remove dummy data after Epsilon whitelists server IP 204.168.186.235
        String dummyJson = """
            {
                "status": 1,
                "title": "Success",
                "type": "success",
                "message": "Get Did number data (MOCK)",
                "data": [
                    {"id": 3064, "did_number": 919484956703, "is_paid_did": 1},
                    {"id": 3065, "did_number": 919484956704, "is_paid_did": 1},
                    {"id": 3066, "did_number": 919484956705, "is_paid_did": 1},
                    {"id": 3067, "did_number": 919484956706, "is_paid_did": 1},
                    {"id": 3068, "did_number": 919484956707, "is_paid_did": 1},
                    {"id": 3069, "did_number": 919484956708, "is_paid_did": 1},
                    {"id": 3070, "did_number": 919484956709, "is_paid_did": 1},
                    {"id": 3074, "did_number": 919484956713, "is_paid_did": 1},
                    {"id": 3075, "did_number": 919484956714, "is_paid_did": 1},
                    {"id": 3079, "did_number": 919484956718, "is_paid_did": 1},
                    {"id": 3080, "did_number": 919484956719, "is_paid_did": 1},
                    {"id": 3085, "did_number": 919484956724, "is_paid_did": 1},
                    {"id": 3088, "did_number": 919484956727, "is_paid_did": 1},
                    {"id": 3089, "did_number": 919484956728, "is_paid_did": 1},
                    {"id": 3090, "did_number": 919484956729, "is_paid_did": 1},
                    {"id": 3091, "did_number": 919484956730, "is_paid_did": 1},
                    {"id": 3093, "did_number": 919484956732, "is_paid_did": 1},
                    {"id": 3094, "did_number": 919484956733, "is_paid_did": 1},
                    {"id": 3095, "did_number": 919484956734, "is_paid_did": 1},
                    {"id": 3097, "did_number": 919484956736, "is_paid_did": 1},
                    {"id": 3098, "did_number": 919484956737, "is_paid_did": 1},
                    {"id": 3099, "did_number": 919484956738, "is_paid_did": 1},
                    {"id": 3100, "did_number": 919484956739, "is_paid_did": 1},
                    {"id": 3101, "did_number": 919484956740, "is_paid_did": 1},
                    {"id": 3105, "did_number": 919484956744, "is_paid_did": 1},
                    {"id": 3116, "did_number": 919484956755, "is_paid_did": 1},
                    {"id": 3126, "did_number": 919484956765, "is_paid_did": 1},
                    {"id": 3127, "did_number": 919484956766, "is_paid_did": 1}
                ]
            }
            """;

        try {
            ObjectMapper mapper = new ObjectMapper();
            DidListResponse response = mapper.readValue(dummyJson, DidListResponse.class);
            log.info("Returning MOCK DID list ({} numbers)", response.getData().size());
            return Mono.just(response);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to parse mock DID data", e));
        }
    }

    public Mono<DidListResponse> getDidList1() {

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
