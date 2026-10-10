package com.dalai.llama.tenant.youtube.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code youtube_oauth_state} and {@code youtube_channel_connection} (V35). */
@Repository
@RequiredArgsConstructor
public class YouTubeConnectionStore {

    public enum OwnerType { CREATOR, PLATFORM }

    public enum Status { ACTIVE, NEEDS_RECONNECT, REVOKED }

    public record PendingState(OwnerType ownerType, UUID tenantId, String startedBy, String verifierEnc) {
    }

    public record Connection(UUID id, OwnerType ownerType, UUID tenantId, String channelId, String channelTitle,
                             String channelThumbnail, String scopes, String refreshTokenEnc, Status status, String lastError,
                             Instant connectedAt) {
    }

    private static final String COLUMNS = "id, owner_type, tenant_id, channel_id, channel_title, channel_thumbnail, scopes,"
            + " refresh_token_enc, status, last_error, connected_at";
    private static final RowMapper<Connection> ROW = (rs, n) -> new Connection(rs.getObject(1, UUID.class),
            OwnerType.valueOf(rs.getString(2)), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), Status.valueOf(rs.getString(9)), rs.getString(10), rs.getTimestamp(11).toInstant());

    private final JdbcTemplate jdbc;

    // ---- OAuth state ----

    public void saveState(String stateHash, OwnerType ownerType, UUID tenantId, String startedBy, String verifierEnc, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO youtube_oauth_state (state_hash, owner_type, tenant_id, started_by, verifier_enc, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)""", stateHash, ownerType.name(), tenantId, startedBy, verifierEnc, Timestamp.from(expiresAt));
    }

    /** Takes a state exactly once: unused, unexpired, and marked used in the same statement. */
    public Optional<PendingState> consumeState(String stateHash, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE youtube_oauth_state SET used_at = ?
                WHERE state_hash = ? AND used_at IS NULL AND expires_at > ?
                RETURNING owner_type, tenant_id, started_by, verifier_enc""",
                Timestamp.from(now), stateHash, Timestamp.from(now));
        return rows.stream().findFirst().map(r -> new PendingState(OwnerType.valueOf((String) r.get("owner_type")),
                (UUID) r.get("tenant_id"), (String) r.get("started_by"), (String) r.get("verifier_enc")));
    }

    public int purgeStatesBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM youtube_oauth_state WHERE expires_at < ?", Timestamp.from(cutoff));
    }

    // ---- connections ----

    public Optional<Connection> creatorConnection(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_channel_connection WHERE owner_type = 'CREATOR' AND tenant_id = ?"
                + " AND status <> 'REVOKED'", ROW, tenantId).stream().findFirst();
    }

    public Optional<Connection> platformConnection() {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_channel_connection WHERE owner_type = 'PLATFORM' AND status <> 'REVOKED'"
                + " ORDER BY connected_at DESC LIMIT 1", ROW).stream().findFirst();
    }

    public Optional<Connection> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_channel_connection WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Live connection of this channel by another creator, if any. */
    public boolean channelTakenByOtherCreator(String channelId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM youtube_channel_connection
                               WHERE owner_type = 'CREATOR' AND channel_id = ? AND status <> 'REVOKED' AND tenant_id <> ?)""",
                Boolean.class, channelId, tenantId));
    }

    public UUID insert(OwnerType ownerType, UUID tenantId, String channelId, String title, String thumbnail, String scopes,
                       String refreshTokenEnc, String connectedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO youtube_channel_connection (id, owner_type, tenant_id, channel_id, channel_title, channel_thumbnail,
                    scopes, refresh_token_enc, status, connected_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""",
                id, ownerType.name(), tenantId, channelId, title, thumbnail, scopes, refreshTokenEnc, connectedBy);
        return id;
    }

    /** Reconnecting the same channel refreshes its token and scopes in place. */
    public void reconnect(UUID id, String title, String thumbnail, String scopes, String refreshTokenEnc, String connectedBy) {
        jdbc.update("""
                UPDATE youtube_channel_connection SET channel_title = ?, channel_thumbnail = ?, scopes = ?, refresh_token_enc = ?,
                    status = 'ACTIVE', last_error = NULL, connected_by = ?, connected_at = NOW(), updated_at = NOW()
                WHERE id = ?""", title, thumbnail, scopes, refreshTokenEnc, connectedBy, id);
    }

    public void markNeedsReconnect(UUID id, String error) {
        jdbc.update("UPDATE youtube_channel_connection SET status = 'NEEDS_RECONNECT', last_error = ?, updated_at = NOW() WHERE id = ?",
                abbreviate(error), id);
    }

    public void revoke(UUID id) {
        jdbc.update("""
                UPDATE youtube_channel_connection SET status = 'REVOKED', refresh_token_enc = NULL, updated_at = NOW()
                WHERE id = ?""", id);
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
