package com.dalai.llama.llmgateway.service.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Voice cloning against a stub ElevenLabs: the sample goes up under the multipart field "files"
 * (the bracketed "files[]" gets 422 "body.files: Field required" and nothing is cloned). */
class ElevenLabsCloneTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void theVoiceSampleIsUploadedAsFilesAndTheVoiceIdComesBack() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/voices/add", e -> {
            key.set(e.getRequestHeaders().getFirst("xi-api-key"));
            body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] reply = "{\"voice_id\":\"voice-123\"}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, reply.length);
            try (OutputStream out = e.getResponseBody()) { out.write(reply); }
        });
        server.start();

        UUID cast = UUID.randomUUID();
        ProviderRequestContext context = new ProviderRequestContext("tenant-1", UUID.randomUUID());
        PreProductionVoiceReferenceClient references = mock(PreProductionVoiceReferenceClient.class);
        when(references.fetch(any(), eq(cast))).thenReturn(Mono.just(new PreProductionVoiceReferenceClient.VoiceReference(
                new byte[]{1, 2, 3, 4}, "hero-voice.mp3", MediaType.parseMediaType("audio/mpeg"))));
        ElevenLabsProvider provider = new ElevenLabsProvider("http://localhost:" + server.getAddress().getPort(), "test-key", references);

        LlmResponse response = provider.generate(new CanonicalRequest("elevenlabs-voice-clone", "voice_clone", List.of(),
                Map.of("cast_profile_id", cast.toString(), "name", "Unsung hero"), 10000, List.of(), null, context)).block();

        assertThat(response.content()).isEqualTo("voice-123");
        assertThat(key.get()).isEqualTo("test-key");
        assertThat(body.get()).contains("name=\"files\"; filename=\"hero-voice.mp3\"").doesNotContain("files[]");
        assertThat(body.get()).contains("name=\"name\"").contains("Unsung hero");
    }
}
