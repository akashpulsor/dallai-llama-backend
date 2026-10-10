package com.dalai.llama.tenant.leadmanagement.brand;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Stateless brand sessions: {@code v1.<contactId>.<expiresEpochSeconds>.<hmac>}, sent by the
 * public pages in the {@code X-Brand-Session} header. Nothing is stored server side; tampering with
 * any part breaks the HMAC. A header rather than a cookie, so it works the same on every host the
 * public pages run on, with no cross-site cookie rules involved. */
@Slf4j
@Service
public class BrandSessionService {

    public static final String HEADER = "X-Brand-Session";
    private static final String VERSION = "v1";

    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;

    public BrandSessionService(BrandProperties properties, Clock clock) {
        this.clock = clock;
        this.ttlSeconds = properties.sessionDays() * 86_400L;
        if (properties.sessionSecret() == null || properties.sessionSecret().isBlank()) {
            log.warn("brands.session-secret is not set; brand sessions end whenever tenant-service restarts");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = properties.sessionSecret().getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Session(String token, Instant expiresAt) {
    }

    public Session issue(UUID brandContactId) {
        Instant expires = clock.instant().plusSeconds(ttlSeconds);
        String payload = VERSION + "." + brandContactId + "." + expires.getEpochSecond();
        return new Session(payload + "." + sign(payload), expires);
    }

    /** The brand behind a session token; empty when missing, malformed, tampered with or expired. */
    public Optional<UUID> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        String payload = token.substring(0, lastDot);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                token.substring(lastDot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) return Optional.empty();
        try {
            if (Instant.ofEpochSecond(Long.parseLong(parts[2])).isBefore(clock.instant())) return Optional.empty();
            return Optional.of(UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** For controllers: the signed-in brand, or a 401-mapped exception. */
    public UUID require(String token) {
        return resolve(token).orElseThrow(BrandNotSignedInException::new);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign brand session", e);
        }
    }
}
