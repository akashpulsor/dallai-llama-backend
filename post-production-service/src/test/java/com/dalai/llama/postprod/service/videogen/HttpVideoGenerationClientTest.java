package com.dalai.llama.postprod.service.videogen;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URLs this client calls.
 *
 * <p>Both of these were creator-facing {@code /v1/**} routes and both failed in production: the jobs
 * list as Istio's "403 RBAC: access denied", the video URL as a Spring Security 401. Neither failure
 * was visible from the code -- only from the box -- so the paths are asserted here.
 */
class HttpVideoGenerationClientTest {

    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static WebClient.Builder capturing(AtomicReference<ClientRequest> seen, String body) {
        return WebClient.builder().exchangeFunction(request -> {
            seen.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
    }

    @Test
    void theJobsListIsOnTheInternalPath() {
        AtomicReference<ClientRequest> seen = new AtomicReference<>();
        HttpVideoGenerationClient client =
                new HttpVideoGenerationClient(capturing(seen, "[]"), "http://video-gen", 5000);

        client.listJobsForProject(TENANT, PROJECT);

        String path = seen.get().url().getPath();
        assertAll(
                () -> assertEquals("/api/v1/internal/tenants/" + TENANT + "/projects/" + PROJECT
                        + "/shot-jobs", path),
                () -> assertTrue(path.startsWith("/api/v1/internal/"),
                        "was /v1/projects/{id}/jobs, which Istio refuses service-to-service"));
    }

    @Test
    void theVideoUrlIsOnTheInternalPathAndReadsAValueNotARedirect() {
        AtomicReference<ClientRequest> seen = new AtomicReference<>();
        HttpVideoGenerationClient client = new HttpVideoGenerationClient(
                capturing(seen, "{\"url\":\"https://minio/signed.mp4\"}"), "http://video-gen", 5000);

        String url = client.getShotVideoUrl(TENANT, JOB);

        String path = seen.get().url().getPath();
        assertAll(
                () -> assertEquals("/api/v1/internal/tenants/" + TENANT + "/jobs/" + JOB + "/video-url", path),
                () -> assertTrue(path.startsWith("/api/v1/internal/"),
                        "was /v1/jobs/{id}/video, which needs a JWT this call cannot supply"),
                () -> assertEquals("https://minio/signed.mp4", url));
    }
}
