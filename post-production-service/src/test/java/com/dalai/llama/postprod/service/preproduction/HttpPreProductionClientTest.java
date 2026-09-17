package com.dalai.llama.postprod.service.preproduction;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this client actually puts on the wire.
 *
 * <p>Pinned because the prefix is what keeps going wrong. Three separate cross-service reads in this
 * codebase were written against a creator-facing {@code /v1/**} route and failed in production --
 * once as Istio's "RBAC: access denied" and twice as a Spring Security 401, because a call made from
 * a background thread has no JWT to send. The URL is the bug, so the URL is the assertion.
 */
class HttpPreProductionClientTest {

    private static final String DIALOGUE_JSON = """
            {"projectId":"11111111-1111-1111-1111-111111111111",
             "scriptId":"22222222-2222-2222-2222-222222222222",
             "shotRef":"S1","shotNumber":3,"character":"NARRATOR",
             "dialogueScript":"Say the thing.","sourceDialogueLanguage":"en",
             "languageCode":"en-IN","referenceAudioUrl":"https://example/audio.wav"}
            """;

    /** Captures the outgoing request and answers with canned JSON -- no server involved. */
    private static WebClient.Builder capturing(AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> seen,
                                               String body) {
        return WebClient.builder().exchangeFunction(request -> {
            seen.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
    }

    @Test
    void asksForDialogueOnTheInternalPathAndSendsNoTenantHeader() {
        AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> seen = new AtomicReference<>();
        HttpPreProductionClient client =
                new HttpPreProductionClient(capturing(seen, DIALOGUE_JSON), "http://pre-production", 5000);

        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        PreProductionShotDetails details = client.getShotDialogue(tenantId, projectId, "S1");

        URI uri = seen.get().url();
        assertAll(
                () -> assertEquals("/api/v1/internal/tenants/" + tenantId + "/projects/" + projectId
                        + "/shots/S1/dialogue", uri.getPath(), "must be on the internal path"),
                () -> assertTrue(uri.getPath().startsWith("/api/v1/internal/"),
                        "cross-service reads belong on /api/v1/internal/**"),
                // Tenant travels in the path because the internal chain is permitAll and there is no
                // JWT behind this call to derive it from.
                () -> assertNull(seen.get().headers().getFirst("X-Tenant-ID"),
                        "tenant comes from the path, not a header"),
                // scriptId used to ride along as a query param that the server discarded.
                () -> assertNull(uri.getQuery(), "no query string: scriptId was never read"));

        // Every field the dub pipeline reads has to survive the wire -- the two records are
        // structural copies in different services with no shared module, so a rename on one side
        // and not the other fails silently as a null.
        assertAll(
                () -> assertEquals("S1", details.shotRef()),
                () -> assertEquals(3, details.shotNumber()),
                () -> assertEquals("NARRATOR", details.character()),
                () -> assertEquals("Say the thing.", details.dialogueScript()),
                () -> assertEquals("en", details.sourceDialogueLanguage()),
                () -> assertEquals("en-IN", details.languageCode()),
                () -> assertEquals("https://example/audio.wav", details.referenceAudioUrl()));
    }
}
