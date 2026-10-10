package com.dalai.llama.tenant.youtube.oauth;

import com.dalai.llama.tenant.security.CredentialEncryptorConfig;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
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
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/** Rules 31–33 on a real Postgres, with Google stubbed by a local HTTP server: PKCE + single-use
 * state, encrypted refresh tokens, the connected channel becoming the creator's verified channel,
 * channel ownership conflicts, missing permissions, dead grants, disconnect and the ops channel. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "dalaillama.credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "google-oauth.client-id=client-123", "google-oauth.client-secret=secret-456"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({YouTubeConnectionService.class, YouTubeConnectionStore.class, GoogleOAuthClient.class, CredentialEncryptorConfig.class,
        CreatorProfileService.class, HandlePolicy.class, YouTubeConnectionDbTest.TestConfig.class})
class YouTubeConnectionDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final HttpServer GOOGLE = google();
    private static final AtomicReference<String> SCOPE = new AtomicReference<>();
    private static final AtomicReference<String> CHANNEL = new AtomicReference<>();
    private static final AtomicReference<String> REFRESH_ERROR = new AtomicReference<>();
    private static final AtomicReference<String> LAST_VERIFIER = new AtomicReference<>();
    private static final AtomicInteger REVOKES = new AtomicInteger();

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + GOOGLE.getAddress().getPort();
        registry.add("google-oauth.token-url", () -> base + "/token");
        registry.add("google-oauth.revoke-url", () -> base + "/revoke");
        registry.add("youtube.base-url", () -> base);
        registry.add("google-oauth.creator-return-url", () -> "https://creator.test/marketing");
        registry.add("google-oauth.platform-return-url", () -> "https://ops.test/admin");
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

    @MockBean private ChannelImportService importService;
    @Autowired private YouTubeConnectionService connections;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    private UUID riya;

    @BeforeEach
    void seed() {
        wipe(jdbc);
        jdbc.update("DELETE FROM youtube_channel_connection");
        jdbc.update("DELETE FROM youtube_oauth_state");
        riya = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(riya, "Riya Motion");
        SCOPE.set(YouTubeConnectionService.SCOPE_YOUTUBE + " " + YouTubeConnectionService.SCOPE_ANALYTICS);
        CHANNEL.set("UCriya000000000000000000");
        REFRESH_ERROR.set(null);
        REVOKES.set(0);
    }

    @AfterAll
    static void stop() {
        GOOGLE.stop(0);
    }

    @Test
    void aCreatorConnectsWithPkceAndTheChannelBecomesTheirVerifiedChannel() {
        String url = connections.startForCreator(riya, "kc-user-1");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=client-123")
                .contains("code_challenge_method=S256", "access_type=offline", "prompt=consent",
                        "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyt-analytics.readonly");
        String state = param(url, "state");

        String back = connections.complete(state, "auth-code", null);

        assertThat(back).isEqualTo("https://creator.test/marketing?youtube=connected");
        assertThat(YouTubeConnectionService.challenge(LAST_VERIFIER.get())).isEqualTo(param(url, "code_challenge"));
        YouTubeConnectionService.ConnectionView view = connections.creatorView(riya);
        assertThat(view.connected()).isTrue();
        assertThat(view.canPublish()).isTrue();
        assertThat(view.canReadAnalytics()).isTrue();
        assertThat(jdbc.queryForObject("SELECT refresh_token_enc FROM youtube_channel_connection", String.class))
                .isNotBlank().doesNotContain("refresh-xyz");
        assertThat(jdbc.queryForMap("SELECT status, verified_via, channel_id FROM creator_youtube_channel WHERE tenant_id = ?", riya))
                .containsEntry("status", "VERIFIED").containsEntry("verified_via", "OAUTH").containsEntry("channel_id", CHANNEL.get());
        verify(importService).importAll(riya);

        // The state works once.
        assertThat(connections.complete(state, "auth-code", null)).endsWith("youtube=expired");
    }

    @Test
    void aChannelAnotherCreatorOwnsCannotBeConnectedAndTheGrantIsRevoked() {
        UUID arjun = tenant(jdbc, "Arjun");
        profileService.ensureProfile(arjun, "Arjun");
        verifiedChannel(jdbc, arjun, CHANNEL.get(), NOW);

        String back = connections.complete(param(connections.startForCreator(riya, "kc-user-1"), "state"), "auth-code", null);

        assertThat(back).endsWith("youtube=taken");
        assertThat(REVOKES.get()).isEqualTo(1);
        assertThat(connections.creatorView(riya).connected()).isFalse();
    }

    @Test
    void withoutTheYouTubePermissionNothingIsSaved() {
        SCOPE.set(YouTubeConnectionService.SCOPE_ANALYTICS);
        assertThat(connections.complete(param(connections.startForCreator(riya, "u"), "state"), "auth-code", null))
                .endsWith("youtube=missing_permission");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM youtube_channel_connection", Integer.class)).isZero();
        assertThat(connections.complete(param(connections.startForCreator(riya, "u"), "state"), null, "access_denied"))
                .endsWith("youtube=denied");
    }

    @Test
    void aDeadGrantAsksForReconnectAndDisconnectRevokes() {
        connections.complete(param(connections.startForCreator(riya, "u"), "state"), "auth-code", null);
        var connection = connections.creatorConnection(riya).orElseThrow();
        connections.forget(connection.id());
        REFRESH_ERROR.set("invalid_grant");

        assertThatThrownBy(() -> connections.accessToken(connection)).isInstanceOf(YouTubeConnectionService.ReconnectRequiredException.class);
        assertThat(connections.creatorView(riya).status()).isEqualTo(YouTubeConnectionStore.Status.NEEDS_RECONNECT);

        connections.disconnectCreator(riya);
        assertThat(REVOKES.get()).isEqualTo(1);
        assertThat(connections.creatorView(riya).connected()).isFalse();
    }

    @Test
    void opsConnectTheOfficialChannelSeparately() {
        CHANNEL.set("UCofficial00000000000000");
        String back = connections.complete(param(connections.startForPlatform("ops"), "state"), "auth-code", null);
        assertThat(back).isEqualTo("https://ops.test/admin?youtube=connected");
        assertThat(connections.platformAccessToken()).contains("access-abc");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_youtube_channel", Integer.class)).isZero();
    }

    private static String param(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError(name + " missing from " + url);
    }

    private static HttpServer google() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/token", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("grant_type=authorization_code")) {
                    LAST_VERIFIER.set(form(body, "code_verifier"));
                    reply(exchange, 200, "{\"access_token\":\"access-abc\",\"refresh_token\":\"refresh-xyz\",\"expires_in\":3600,"
                            + "\"scope\":\"" + SCOPE.get() + "\"}");
                } else if (REFRESH_ERROR.get() != null) {
                    reply(exchange, 400, "{\"error\":\"" + REFRESH_ERROR.get() + "\",\"error_description\":\"Token has been expired or revoked.\"}");
                } else {
                    reply(exchange, 200, "{\"access_token\":\"access-new\",\"expires_in\":3600}");
                }
            });
            server.createContext("/revoke", exchange -> {
                REVOKES.incrementAndGet();
                reply(exchange, 200, "");
            });
            server.createContext("/channels", exchange -> reply(exchange, 200, "{\"items\":[{\"id\":\"" + CHANNEL.get() + "\","
                    + "\"snippet\":{\"title\":\"Riya Motion\",\"thumbnails\":{\"high\":{\"url\":\"https://yt3/riya.jpg\"}}},"
                    + "\"contentDetails\":{\"relatedPlaylists\":{\"uploads\":\"UU" + CHANNEL.get().substring(2) + "\"}}}]}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(String body, String key) {
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }
}
