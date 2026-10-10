package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.extension.ExtensionTokenService;
import com.dalai.llama.tenant.security.CredentialEncryptor;
import com.dalai.llama.tenant.security.CredentialEncryptorConfig;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.oauth.GoogleOAuthClient;
import com.dalai.llama.tenant.youtube.oauth.GoogleOAuthProperties;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.EditRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.JobView;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishRequest;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Privacy;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Status;
import com.dalai.llama.tenant.youtube.service.ChannelImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Rules 34–36 and 38 on a real Postgres. YouTube and MinIO are a local stub that implements the
 * resumable protocol (308 + Range) and Range reads, so the chunking, the resume after a failed
 * chunk, the thumbnail and edits run for real; Google's token endpoint is stubbed too. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "dalaillama.credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "google-oauth.client-id=client-123", "google-oauth.client-secret=secret-456"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({YouTubePublishService.class, YouTubePublishWorker.class, YouTubePublishStore.class, YouTubeApiClient.class,
        FilmRangeReader.class, YouTubeConnectionService.class, YouTubeConnectionStore.class, GoogleOAuthClient.class,
        CredentialEncryptorConfig.class, ExtensionTokenService.class, CreatorProfileService.class, HandlePolicy.class,
        YouTubePublishDbTest.TestConfig.class})
class YouTubePublishDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    /** 9 MiB: one full 8 MiB chunk plus a 1 MiB tail. */
    private static final int FILM_SIZE = 9 * 1024 * 1024;
    private static final byte[] FILM = film();
    private static final HttpServer STUB = stub();
    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final AtomicBoolean FAIL_NEXT_CHUNK = new AtomicBoolean();
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final AtomicInteger THUMBNAILS = new AtomicInteger();
    private static final List<String> CHUNK_RANGES = new CopyOnWriteArrayList<>();
    private static final List<String> METADATA = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("google-oauth.token-url", () -> base + "/token");
        registry.add("youtube.base-url", () -> base + "/data");
        registry.add("youtube.upload-base-url", () -> base + "/upload");
    }

    @TestConfiguration
    @EnableConfigurationProperties({GoogleOAuthProperties.class, YouTubeProperties.class, ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean private PreProductionShowcaseClient preProduction;
    @MockBean private ChannelImportService importService;
    @Autowired private YouTubePublishService publishing;
    @Autowired private YouTubePublishWorker worker;
    @Autowired private ExtensionTokenService extensionTokens;
    @Autowired private CredentialEncryptor encryptor;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID riya;
    private final UUID project = UUID.randomUUID();

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        jdbc.update("DELETE FROM youtube_channel_connection");
        riya = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(riya, "Riya Motion");
        jdbc.update("""
                INSERT INTO youtube_channel_connection (id, owner_type, tenant_id, channel_id, channel_title, scopes, refresh_token_enc,
                    status, connected_by)
                VALUES (?, 'CREATOR', ?, 'UCriya000000000000000000', 'Riya Motion', ?, ?, 'ACTIVE', 'kc-user')""",
                UUID.randomUUID(), riya, YouTubeConnectionService.SCOPE_YOUTUBE, encryptor.encrypt("refresh-xyz"));
        filmWithConsent(true);
        RECEIVED.set(0);
        FAIL_NEXT_CHUNK.set(false);
        SESSIONS.set(0);
        THUMBNAILS.set(0);
        CHUNK_RANGES.clear();
        METADATA.clear();
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Test
    void anUploadIsChunkedAndResumesFromYouTubesOffsetAfterAFailedChunk() {
        JobView job = publishing.publish(riya, "kc-user", request(Privacy.UNLISTED, null, false, "k1"), "WEB");
        publishing.setThumbnail(riya, job.id(), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}, "image/jpeg");
        FAIL_NEXT_CHUNK.set(false);

        // First run: chunk 1 lands, chunk 2 hits a 503 → retried later, session kept.
        failSecondChunk();
        assertThat(worker.runOnce()).isTrue();
        JobView afterFailure = publishing.job(riya, job.id());
        assertThat(afterFailure.status()).isEqualTo(Status.UPLOADING);
        assertThat(afterFailure.bytesSent()).isEqualTo(8L * 1024 * 1024);
        assertThat(afterFailure.attempts()).isEqualTo(1);

        // Second run, after the backoff: resumes at 8 MiB, not from zero, in the same session.
        clock.set(NOW.plus(Duration.ofMinutes(2)));
        assertThat(worker.runOnce()).isTrue();
        JobView done = publishing.job(riya, job.id());
        assertThat(done.status()).isEqualTo(Status.PUBLISHED);
        assertThat(done.youtubeVideoId()).isEqualTo("vid123");
        assertThat(done.progressPercent()).isEqualTo(100);
        assertThat(SESSIONS.get()).isEqualTo(1);
        assertThat(CHUNK_RANGES).containsExactly("bytes 0-8388607/9437184", "bytes 8388608-9437183/9437184",
                "bytes 8388608-9437183/9437184");
        assertThat(RECEIVED.get()).isEqualTo(FILM_SIZE);
        assertThat(THUMBNAILS.get()).isEqualTo(1);
        assertThat(METADATA.get(0)).contains("\"privacyStatus\":\"unlisted\"", "\"containsSyntheticMedia\":true");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void publicOrScheduledNeedsConfirmation_andNonPrivateNeedsClientConsent() {
        assertThatThrownBy(() -> publishing.publish(riya, "u", request(Privacy.PUBLIC, null, false, "a"), "WEB"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Confirm");
        assertThatThrownBy(() -> publishing.publish(riya, "u", request(Privacy.PRIVATE, NOW.plus(Duration.ofDays(1)), false, "b"), "WEB"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publishing.publish(riya, "u", request(Privacy.PRIVATE, NOW.plus(Duration.ofMinutes(5)), true, "c"), "WEB"))
                .hasMessageContaining("15 minutes");

        filmWithConsent(false);
        assertThatThrownBy(() -> publishing.publish(riya, "u", request(Privacy.UNLISTED, null, false, "d"), "WEB"))
                .hasMessageContaining("private");
        assertThat(publishing.publish(riya, "u", request(Privacy.PRIVATE, null, false, "e"), "WEB").status()).isEqualTo(Status.QUEUED);
    }

    @Test
    void theSameKeyOrAnActiveJobForTheSameFilmNeverStartsASecondUpload() {
        JobView first = publishing.publish(riya, "u", request(Privacy.PUBLIC, null, true, "same"), "WEB");
        assertThat(publishing.publish(riya, "u", request(Privacy.PUBLIC, null, true, "same"), "WEB").id()).isEqualTo(first.id());
        assertThat(publishing.publish(riya, "u", request(Privacy.PUBLIC, null, true, "other-key"), "EXTENSION").id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM youtube_publish_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void publishedVideosCanBeEditedAndACancelledJobIsLeftAlone() {
        JobView job = publishing.publish(riya, "u", request(Privacy.PRIVATE, null, false, "k"), "WEB");
        worker.runOnce();
        JobView edited = publishing.edit(riya, job.id(), new EditRequest("New title", "New text", List.of("ghee"), Privacy.PUBLIC, null, true));
        assertThat(edited.title()).isEqualTo("New title");
        assertThat(edited.youtubePrivacy()).isEqualTo("public");
        assertThat(METADATA.get(METADATA.size() - 1)).contains("\"id\":\"vid123\"", "\"title\":\"New title\"");

        UUID other = UUID.randomUUID();
        when(preProduction.source(riya, other)).thenReturn(source(other, true));
        JobView queued = publishing.publish(riya, "u", new PublishRequest(other, "Another", "", null, null, Privacy.PRIVATE, null, false, "k2"), "WEB");
        publishing.cancel(riya, queued.id());
        assertThat(worker.runOnce()).isFalse();
        assertThat(publishing.job(riya, queued.id()).status()).isEqualTo(Status.CANCELLED);
    }

    @Test
    void extensionTokensAreHashedScopedAndRevocable() {
        var minted = extensionTokens.mint(riya, "kc-user", "Work laptop");
        assertThat(minted.token()).startsWith("dlx_");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM extension_token", String.class)).doesNotContain(minted.token());
        assertThat(extensionTokens.require(minted.token()).tenantId()).isEqualTo(riya);

        extensionTokens.revoke(riya, minted.id());
        assertThatThrownBy(() -> extensionTokens.require(minted.token())).isInstanceOf(ExtensionTokenService.ExtensionNotPairedException.class);

        var expiring = extensionTokens.mint(riya, "kc-user", null);
        clock.set(NOW.plus(Duration.ofDays(31)));
        assertThatThrownBy(() -> extensionTokens.require(expiring.token())).isInstanceOf(ExtensionTokenService.ExtensionNotPairedException.class);
        assertThatThrownBy(() -> extensionTokens.require("not-a-token")).isInstanceOf(ExtensionTokenService.ExtensionNotPairedException.class);
    }

    private PublishRequest request(Privacy privacy, Instant publishAt, boolean confirm, String key) {
        return new PublishRequest(project, "Masala chai launch", "Made with Dalai Llama", List.of("chai", "ad"), null, privacy, publishAt,
                confirm, key);
    }

    private void filmWithConsent(boolean consent) {
        when(preProduction.source(any(), any())).thenReturn(source(project, consent));
    }

    private static ShowcaseSource source(UUID projectId, boolean consent) {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        return new ShowcaseSource(projectId, "Masala chai launch", "CLIENT_LOCKED", true, at, new BigDecimal("30"), 1920, 1080, at,
                consent ? "2026-10" : null, consent ? at : null, 10, 1, 1, 1,
                "http://localhost:" + STUB.getAddress().getPort() + "/film");
    }

    private static void failSecondChunk() {
        FAIL_NEXT_CHUNK.set(true);
    }

    private static byte[] film() {
        byte[] bytes = new byte[FILM_SIZE];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        return bytes;
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            // The upload streams the film from this same server: handlers must run concurrently.
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/token", e -> reply(e, 200, "{\"access_token\":\"access-abc\",\"expires_in\":3600}", null));
            server.createContext("/film", e -> {
                String range = e.getRequestHeaders().getFirst("Range");
                String[] parts = range.replace("bytes=", "").split("-");
                int from = Integer.parseInt(parts[0]);
                int to = Math.min(Integer.parseInt(parts[1]), FILM_SIZE - 1);
                e.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + FILM_SIZE);
                e.sendResponseHeaders(206, to - from + 1L);
                try (OutputStream out = e.getResponseBody()) { out.write(FILM, from, to - from + 1); }
                e.close();
            });
            server.createContext("/upload/videos", e -> {
                METADATA.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                SESSIONS.incrementAndGet();
                reply(e, 200, "", "http://localhost:" + server.getAddress().getPort() + "/session");
            });
            server.createContext("/session", e -> {
                String contentRange = e.getRequestHeaders().getFirst("Content-Range");
                if (contentRange.startsWith("bytes */")) {
                    e.getRequestBody().readAllBytes();
                    if (RECEIVED.get() >= FILM_SIZE) { reply(e, 200, done(), null); return; }
                    if (RECEIVED.get() > 0) e.getResponseHeaders().add("Range", "bytes=0-" + (RECEIVED.get() - 1));
                    e.sendResponseHeaders(308, -1);
                    e.close();
                    return;
                }
                CHUNK_RANGES.add(contentRange);
                byte[] chunk = e.getRequestBody().readAllBytes();
                if (FAIL_NEXT_CHUNK.get() && RECEIVED.get() > 0) {
                    FAIL_NEXT_CHUNK.set(false);
                    reply(e, 503, "{\"error\":{\"message\":\"backend error\"}}", null);
                    return;
                }
                RECEIVED.addAndGet(chunk.length);
                if (RECEIVED.get() >= FILM_SIZE) { reply(e, 200, done(), null); return; }
                e.getResponseHeaders().add("Range", "bytes=0-" + (RECEIVED.get() - 1));
                e.sendResponseHeaders(308, -1);
                e.close();
            });
            server.createContext("/upload/thumbnails/set", e -> {
                e.getRequestBody().readAllBytes();
                THUMBNAILS.incrementAndGet();
                reply(e, 200, "{}", null);
            });
            server.createContext("/data/videos", e -> {
                METADATA.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply(e, 200, "{\"id\":\"vid123\",\"status\":{\"privacyStatus\":\"public\"}}", null);
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String done() {
        return "{\"id\":\"vid123\",\"status\":{\"privacyStatus\":\"unlisted\"}}";
    }

    private static void reply(HttpExchange e, int status, String body, String location) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (location != null) e.getResponseHeaders().add("Location", location);
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
        e.close();
    }
}
