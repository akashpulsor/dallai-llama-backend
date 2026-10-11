package com.dalai.llama.tenant.youtube.oauth;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.security.CredentialEncryptor;
import com.dalai.llama.tenant.youtube.client.YouTubeUnavailableException;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Connection;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.OwnerType;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.PendingState;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionStore.Status;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.youtube.service.ChannelImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Rules 31–33: connecting a YouTube channel with Google OAuth (authorization code + PKCE, state kept
 * server-side), for a creator's own channel or, by ops, the official Dalaillama channel. Refresh
 * tokens are stored encrypted and never leave this service; callers get short-lived access tokens. */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeConnectionService implements PlatformYouTubeTokens {

    public static final String SCOPE_YOUTUBE = "https://www.googleapis.com/auth/youtube";
    public static final String SCOPE_ANALYTICS = "https://www.googleapis.com/auth/yt-analytics.readonly";

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthClient google;
    private final YouTubeConnectionStore store;
    private final CredentialEncryptor encryptor;
    private final ChannelImportService importService;
    private final CreatorProfileService profileService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<UUID, CachedToken> tokens = new ConcurrentHashMap<>();

    private record CachedToken(String value, Instant expiresAt) {
    }

    /** What a creator or ops sees about a connection. Never carries a token. */
    public record ConnectionView(boolean oauthConfigured, boolean connected, Status status, String channelId, String channelTitle,
                                 String channelThumbnail, boolean canPublish, boolean canReadAnalytics, Instant connectedAt,
                                 String lastError) {
    }

    /** Thrown when Google says the stored grant is dead; the UI offers "Reconnect". */
    public static class ReconnectRequiredException extends IllegalStateException {
        public ReconnectRequiredException() {
            super("YouTube access has expired. Reconnect your channel to continue.");
        }
    }

    // ---- starting ----

    @Transactional
    public String startForCreator(UUID tenantId, String subject) {
        return start(OwnerType.CREATOR, tenantId, subject);
    }

    @Transactional
    public String startForPlatform(String subject) {
        return start(OwnerType.PLATFORM, null, subject);
    }

    private String start(OwnerType owner, UUID tenantId, String subject) {
        if (!properties.configured()) throw new YouTubeUnavailableException("Connecting YouTube isn't set up yet");
        String state = PublicTokens.newToken();
        String verifier = PublicTokens.newToken() + PublicTokens.newToken();
        store.saveState(PublicTokens.sha256Hex(state), owner, tenantId, subject, encryptor.encrypt(verifier),
                clock.instant().plus(Duration.ofMinutes(properties.stateMinutes())));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", properties.clientId());
        params.put("redirect_uri", properties.redirectUri());
        params.put("response_type", "code");
        params.put("scope", String.join(" ", properties.scopes()));
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "true");
        params.put("state", state);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
        return properties.authUrl() + "?" + params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    // ---- Google's callback ----

    /** Finishes a connect and returns where to send the browser. Every failure becomes a reason on
     * the return URL; nothing about tokens or the request is echoed back. */
    @Transactional
    public String complete(String state, String code, String error) {
        Optional<PendingState> pending = state == null ? Optional.empty()
                : store.consumeState(PublicTokens.sha256Hex(state), clock.instant());
        if (pending.isEmpty()) return withResult(properties.creatorReturnUrl(), "expired");
        PendingState p = pending.get();
        String back = p.ownerType() == OwnerType.CREATOR ? properties.creatorReturnUrl() : properties.platformReturnUrl();
        if (error != null || code == null) return withResult(back, "denied");
        try {
            GoogleOAuthClient.Tokens granted = google.exchangeCode(code, encryptor.decrypt(p.verifierEnc()));
            Set<String> scopes = Set.copyOf(Arrays.asList(granted.scope().split(" ")));
            if (!scopes.contains(SCOPE_YOUTUBE)) return withResult(back, "missing_permission");
            if (granted.refreshToken() == null) return withResult(back, "no_offline_access");
            GoogleOAuthClient.Channel channel = google.myChannel(granted.accessToken());
            String result = p.ownerType() == OwnerType.CREATOR
                    ? connectCreator(p, channel, granted)
                    : connectPlatform(p, channel, granted);
            return withResult(back, result);
        } catch (IllegalStateException e) {
            log.warn("YouTube connect refused: {}", e.getMessage());
            return withResult(back, "no_channel");
        } catch (RuntimeException e) {
            log.error("YouTube connect failed", e);
            return withResult(back, "failed");
        }
    }

    private String connectCreator(PendingState p, GoogleOAuthClient.Channel channel, GoogleOAuthClient.Tokens granted) {
        UUID tenantId = p.tenantId();
        if (store.channelTakenByOtherCreator(channel.channelId(), tenantId) || verifiedByOtherCreator(channel.channelId(), tenantId)) {
            google.revoke(granted.refreshToken());
            return "taken";
        }
        Optional<String> verifiedChannel = jdbc.queryForList(
                "SELECT channel_id FROM creator_youtube_channel WHERE tenant_id = ? AND status = 'VERIFIED'", String.class, tenantId)
                .stream().findFirst();
        if (verifiedChannel.isPresent() && !verifiedChannel.get().equals(channel.channelId())) {
            google.revoke(granted.refreshToken());
            return "other_channel";
        }
        Optional<Connection> existing = store.creatorConnection(tenantId);
        if (existing.isPresent() && !existing.get().channelId().equals(channel.channelId())) {
            google.revoke(granted.refreshToken());
            return "other_channel";
        }
        UUID id = save(existing, OwnerType.CREATOR, tenantId, channel, granted, p.startedBy());
        if (verifiedChannel.isEmpty()) verifyChannel(tenantId, channel);
        cache(id, granted.accessToken(), granted.expiresInSeconds());
        try {
            importService.importAll(tenantId);
        } catch (RuntimeException e) {
            log.warn("Connected tenant {} but the first import failed: {}", tenantId, e.getMessage());
        }
        return "connected";
    }

    private String connectPlatform(PendingState p, GoogleOAuthClient.Channel channel, GoogleOAuthClient.Tokens granted) {
        Optional<Connection> existing = store.platformConnection();
        if (existing.isPresent() && !existing.get().channelId().equals(channel.channelId())) {
            revokeQuietly(existing.get());
            store.revoke(existing.get().id());
            existing = Optional.empty();
        }
        UUID id = save(existing, OwnerType.PLATFORM, null, channel, granted, p.startedBy());
        cache(id, granted.accessToken(), granted.expiresInSeconds());
        return "connected";
    }

    private UUID save(Optional<Connection> existing, OwnerType owner, UUID tenantId, GoogleOAuthClient.Channel channel,
                      GoogleOAuthClient.Tokens granted, String by) {
        String refresh = encryptor.encrypt(granted.refreshToken());
        if (existing.isPresent()) {
            store.reconnect(existing.get().id(), channel.title(), channel.thumbnailUrl(), granted.scope(), refresh, by);
            return existing.get().id();
        }
        return store.insert(owner, tenantId, channel.channelId(), channel.title(), channel.thumbnailUrl(), granted.scope(), refresh, by);
    }

    /** Signing in as the channel proves ownership: the channel becomes the creator's verified
     * channel (rule 33), replacing any unverified claim. */
    private void verifyChannel(UUID tenantId, GoogleOAuthClient.Channel channel) {
        if (profileService.ensureProfile(tenantId).isEmpty()) return;
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("DELETE FROM creator_youtube_channel WHERE channel_id = ? AND tenant_id <> ? AND status = 'PENDING'",
                channel.channelId(), tenantId);
        jdbc.update("""
                INSERT INTO creator_youtube_channel (tenant_id, channel_id, channel_title, channel_thumbnail_url, uploads_playlist_id,
                    verification_code, status, verified_at, fetched_at, verified_via)
                VALUES (?, ?, ?, ?, ?, 'OAUTH', 'VERIFIED', ?, ?, 'OAUTH')
                ON CONFLICT (tenant_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, channel_title = EXCLUDED.channel_title,
                    channel_thumbnail_url = EXCLUDED.channel_thumbnail_url, uploads_playlist_id = EXCLUDED.uploads_playlist_id,
                    status = 'VERIFIED', verified_at = EXCLUDED.verified_at, fetched_at = EXCLUDED.fetched_at, verified_via = 'OAUTH',
                    updated_at = NOW()""",
                tenantId, channel.channelId(), channel.title(), channel.thumbnailUrl(),
                channel.uploadsPlaylistId() == null ? "UU" + channel.channelId().substring(2) : channel.uploadsPlaylistId(), now, now);
    }

    private boolean verifiedByOtherCreator(String channelId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM creator_youtube_channel WHERE channel_id = ? AND tenant_id <> ? AND status = 'VERIFIED')""",
                Boolean.class, channelId, tenantId));
    }

    // ---- using a connection ----

    public Optional<Connection> creatorConnection(UUID tenantId) {
        return store.creatorConnection(tenantId);
    }

    /** A short-lived access token for this connection; refreshed when it expires. */
    public String accessToken(Connection connection) {
        CachedToken cached = tokens.get(connection.id());
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) return cached.value();
        if (connection.status() != Status.ACTIVE || connection.refreshTokenEnc() == null) throw new ReconnectRequiredException();
        try {
            GoogleOAuthClient.AccessToken fresh = google.refresh(encryptor.decrypt(connection.refreshTokenEnc()));
            cache(connection.id(), fresh.value(), fresh.expiresInSeconds());
            return fresh.value();
        } catch (GoogleOAuthClient.InvalidGrantException e) {
            store.markNeedsReconnect(connection.id(), e.getMessage());
            tokens.remove(connection.id());
            throw new ReconnectRequiredException();
        }
    }

    /** Drops a cached token YouTube rejected (401), so the next call refreshes. */
    public void forget(UUID connectionId) {
        tokens.remove(connectionId);
    }

    @Override
    public Optional<String> platformAccessToken() {
        return store.platformConnection().filter(c -> c.status() == Status.ACTIVE).map(this::accessToken);
    }

    // ---- views and disconnect ----

    public ConnectionView creatorView(UUID tenantId) {
        return view(store.creatorConnection(tenantId));
    }

    public ConnectionView platformView() {
        return view(store.platformConnection());
    }

    @Transactional
    public void disconnectCreator(UUID tenantId) {
        store.creatorConnection(tenantId).ifPresent(this::disconnect);
    }

    @Transactional
    public void disconnectPlatform() {
        store.platformConnection().ifPresent(this::disconnect);
    }

    private void disconnect(Connection c) {
        revokeQuietly(c);
        store.revoke(c.id());
        tokens.remove(c.id());
    }

    private void revokeQuietly(Connection c) {
        if (c.refreshTokenEnc() != null) google.revoke(encryptor.decrypt(c.refreshTokenEnc()));
    }

    private ConnectionView view(Optional<Connection> connection) {
        if (connection.isEmpty()) return new ConnectionView(properties.configured(), false, null, null, null, null, false, false, null, null);
        Connection c = connection.get();
        List<String> scopes = Arrays.asList(c.scopes().split(" "));
        return new ConnectionView(properties.configured(), c.status() == Status.ACTIVE, c.status(), c.channelId(), c.channelTitle(),
                c.channelThumbnail(), scopes.contains(SCOPE_YOUTUBE), scopes.contains(SCOPE_ANALYTICS), c.connectedAt(), c.lastError());
    }

    private void cache(UUID connectionId, String token, long expiresIn) {
        tokens.put(connectionId, new CachedToken(token, clock.instant().plusSeconds(Math.max(60, expiresIn - 120))));
    }

    private static String withResult(String url, String result) {
        return url + (url.contains("?") ? "&" : "?") + "youtube=" + result;
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
