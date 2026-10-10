package com.dalai.llama.tenant.extension;

import com.dalai.llama.tenant.common.token.PublicTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule 38: the Chrome extension's credential. The signed-in web app mints one and hands it to the
 * extension; only its hash is stored; it lasts 30 days and the creator can revoke it any time. It
 * reaches only {@code /api/v1/extension/**}, never the creator's JWT routes or any Google token. */
@Service
@RequiredArgsConstructor
public class ExtensionTokenService {

    static final Duration LIFETIME = Duration.ofDays(30);
    static final int MAX_ACTIVE = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record MintedToken(UUID id, String token, Instant expiresAt) {
    }

    public record TokenView(UUID id, String label, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {
    }

    /** Who an extension request is for. */
    public record ExtensionPrincipal(UUID tenantId, String subject, UUID tokenId) {
    }

    /** Thrown for a missing, unknown, expired or revoked token: 401, pair the extension again. */
    public static class ExtensionNotPairedException extends RuntimeException {
        public ExtensionNotPairedException() {
            super("Connect the extension from Dalai Llama again");
        }
    }

    @Transactional
    public MintedToken mint(UUID tenantId, String subject, String label) {
        Instant now = clock.instant();
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM extension_token WHERE tenant_id = ? AND revoked_at IS NULL AND expires_at > ?""",
                Integer.class, tenantId, Timestamp.from(now));
        if (active != null && active >= MAX_ACTIVE) {
            throw new IllegalStateException("You have " + MAX_ACTIVE + " connected browsers; remove one first");
        }
        UUID id = UUID.randomUUID();
        String token = "dlx_" + PublicTokens.newToken() + PublicTokens.newToken();
        Instant expires = now.plus(LIFETIME);
        jdbc.update("""
                INSERT INTO extension_token (id, tenant_id, user_subject, token_hash, label, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, tenantId, subject, PublicTokens.sha256Hex(token), label == null || label.isBlank() ? "Chrome" : label.trim(),
                Timestamp.from(now), Timestamp.from(expires));
        return new MintedToken(id, token, expires);
    }

    public List<TokenView> list(UUID tenantId) {
        return jdbc.query("""
                SELECT id, label, created_at, last_used_at, expires_at FROM extension_token
                WHERE tenant_id = ? AND revoked_at IS NULL AND expires_at > ? ORDER BY created_at DESC""",
                (rs, n) -> new TokenView(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()),
                tenantId, Timestamp.from(clock.instant()));
    }

    @Transactional
    public void revoke(UUID tenantId, UUID id) {
        if (jdbc.update("UPDATE extension_token SET revoked_at = ? WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL",
                Timestamp.from(clock.instant()), tenantId, id) == 0) {
            throw new IllegalArgumentException("No such connected browser");
        }
    }

    @Transactional
    public ExtensionPrincipal require(String token) {
        if (token == null || !token.startsWith("dlx_")) throw new ExtensionNotPairedException();
        Instant now = clock.instant();
        Optional<ExtensionPrincipal> principal = jdbc.query("""
                UPDATE extension_token SET last_used_at = ?
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                RETURNING tenant_id, user_subject, id""",
                (rs, n) -> new ExtensionPrincipal(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)),
                Timestamp.from(now), PublicTokens.sha256Hex(token), Timestamp.from(now)).stream().findFirst();
        return principal.orElseThrow(ExtensionNotPairedException::new);
    }
}
