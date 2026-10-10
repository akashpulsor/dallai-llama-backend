package com.dalai.llama.tenant.youtube.publish;

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

/** {@code youtube_publish_job} (V35). Jobs are claimed with a lease, so a crashed upload is picked up
 * again once the lease runs out and resumes from YouTube's committed offset. */
@Repository
@RequiredArgsConstructor
public class YouTubePublishStore {

    public enum Status { QUEUED, UPLOADING, PUBLISHED, SCHEDULED, FAILED, CANCELLED }

    public enum Privacy { PRIVATE, UNLISTED, PUBLIC }

    public record NewJob(UUID tenantId, UUID connectionId, UUID projectId, String idempotencyKey, String title, String description,
                         String tags, String categoryId, Privacy privacy, Instant publishAt, Instant publicConfirmedAt, String via,
                         String createdBy, Instant dueAt) {
    }

    public record Job(UUID id, UUID tenantId, UUID connectionId, UUID projectId, String title, String description, String tags,
                      String categoryId, Privacy privacy, Instant publishAt, Instant publicConfirmedAt, boolean hasThumbnail, Status status,
                      String uploadUrlEnc, Long bytesTotal, long bytesSent, String videoId, String youtubePrivacy, int attempts,
                      String lastError, String via, Instant createdAt, Instant updatedAt) {
    }

    private static final String COLUMNS = """
            id, tenant_id, connection_id, source_project_id, title, description, tags, category_id, privacy, publish_at,
            public_confirmed_at, thumbnail IS NOT NULL, status, upload_url_enc, bytes_total, bytes_sent, youtube_video_id,
            youtube_privacy, attempts, last_error, requested_via, created_at, updated_at""";

    private static final RowMapper<Job> ROW = (rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            Privacy.valueOf(rs.getString(9)), instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), rs.getBoolean(12),
            Status.valueOf(rs.getString(13)), rs.getString(14), (Long) rs.getObject(15), rs.getLong(16), rs.getString(17),
            rs.getString(18), rs.getInt(19), rs.getString(20), rs.getString(21), rs.getTimestamp(22).toInstant(),
            rs.getTimestamp(23).toInstant());

    private final JdbcTemplate jdbc;

    public UUID insert(NewJob j) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO youtube_publish_job (id, tenant_id, connection_id, source_project_id, idempotency_key, title, description,
                    tags, category_id, privacy, publish_at, public_confirmed_at, status, requested_via, created_by, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)""",
                id, j.tenantId(), j.connectionId(), j.projectId(), j.idempotencyKey(), j.title(), j.description(), j.tags(),
                j.categoryId(), j.privacy().name(), ts(j.publishAt()), ts(j.publicConfirmedAt()), j.via(), j.createdBy(), ts(j.dueAt()));
        return id;
    }

    public Optional<Job> byKey(UUID tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_publish_job WHERE tenant_id = ? AND idempotency_key = ?", ROW, tenantId, key)
                .stream().findFirst();
    }

    /** A queued or uploading job for this film and channel (rule 34: never two uploads at once). */
    public Optional<Job> activeFor(UUID tenantId, UUID projectId, UUID connectionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_publish_job WHERE tenant_id = ? AND source_project_id = ?"
                + " AND connection_id = ? AND status IN ('QUEUED', 'UPLOADING')", ROW, tenantId, projectId, connectionId).stream().findFirst();
    }

    public Optional<Job> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_publish_job WHERE tenant_id = ? AND id = ?", ROW, tenantId, id)
                .stream().findFirst();
    }

    public Optional<Job> load(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_publish_job WHERE id = ?", ROW, id).stream().findFirst();
    }

    public List<Job> recent(UUID tenantId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM youtube_publish_job WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW, tenantId, limit);
    }

    /** Takes the next due job and leases it until {@code leaseUntil}; empty when nothing is due. */
    public Optional<UUID> claimDue(Instant now, Instant leaseUntil) {
        return jdbc.queryForList("""
                UPDATE youtube_publish_job SET status = 'UPLOADING', locked_until = ?, updated_at = NOW()
                WHERE id = (SELECT id FROM youtube_publish_job
                            WHERE status IN ('QUEUED', 'UPLOADING') AND next_attempt_at <= ?
                              AND (locked_until IS NULL OR locked_until < ?)
                            ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id""", UUID.class, ts(leaseUntil), ts(now), ts(now)).stream().findFirst();
    }

    public Status status(UUID id) {
        return Status.valueOf(jdbc.queryForObject("SELECT status FROM youtube_publish_job WHERE id = ?", String.class, id));
    }

    public void saveSession(UUID id, String uploadUrlEnc, long total) {
        jdbc.update("UPDATE youtube_publish_job SET upload_url_enc = ?, bytes_total = ?, bytes_sent = 0, updated_at = NOW() WHERE id = ?",
                uploadUrlEnc, total, id);
    }

    public void clearSession(UUID id) {
        jdbc.update("UPDATE youtube_publish_job SET upload_url_enc = NULL, bytes_sent = 0, updated_at = NOW() WHERE id = ?", id);
    }

    public void progress(UUID id, long sent, Instant leaseUntil) {
        jdbc.update("UPDATE youtube_publish_job SET bytes_sent = ?, locked_until = ?, updated_at = NOW() WHERE id = ?", sent, ts(leaseUntil), id);
    }

    public void complete(UUID id, String videoId, String youtubePrivacy, Status status) {
        jdbc.update("""
                UPDATE youtube_publish_job SET status = ?, youtube_video_id = ?, youtube_privacy = ?, bytes_sent = COALESCE(bytes_total, bytes_sent),
                    upload_url_enc = NULL, locked_until = NULL, last_error = NULL, updated_at = NOW()
                WHERE id = ?""", status.name(), videoId, youtubePrivacy, id);
    }

    public void retryLater(UUID id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE youtube_publish_job SET attempts = ?, next_attempt_at = ?, locked_until = NULL, last_error = ?, updated_at = NOW()
                WHERE id = ?""", attempts, ts(nextAttemptAt), abbreviate(error), id);
    }

    /** Back to the queue until YouTube's daily quota resets (rule 41); not a failed attempt. */
    public void waitForQuota(UUID id, Instant until, String note) {
        jdbc.update("""
                UPDATE youtube_publish_job SET status = 'QUEUED', next_attempt_at = ?, locked_until = NULL, last_error = ?, updated_at = NOW()
                WHERE id = ?""", ts(until), abbreviate(note), id);
    }

    public void fail(UUID id, String error) {
        jdbc.update("UPDATE youtube_publish_job SET status = 'FAILED', locked_until = NULL, last_error = ?, updated_at = NOW() WHERE id = ?",
                abbreviate(error), id);
    }

    /** A worker that sees CANCELLED between chunks stops. */
    public int cancel(UUID tenantId, UUID id) {
        return jdbc.update("""
                UPDATE youtube_publish_job SET status = 'CANCELLED', locked_until = NULL, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status IN ('QUEUED', 'UPLOADING')""", tenantId, id);
    }

    public int requeue(UUID tenantId, UUID id, Instant now) {
        return jdbc.update("""
                UPDATE youtube_publish_job SET status = 'QUEUED', attempts = 0, next_attempt_at = ?, last_error = NULL, updated_at = NOW()
                WHERE tenant_id = ? AND id = ? AND status = 'FAILED'""", ts(now), tenantId, id);
    }

    public record Thumbnail(byte[] bytes, String contentType) {
    }

    public Optional<Thumbnail> thumbnail(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT thumbnail, thumbnail_type FROM youtube_publish_job WHERE id = ? AND thumbnail IS NOT NULL", id);
        return rows.stream().findFirst().map(r -> new Thumbnail((byte[]) r.get("thumbnail"), (String) r.get("thumbnail_type")));
    }

    public void setThumbnail(UUID id, byte[] bytes, String contentType) {
        jdbc.update("UPDATE youtube_publish_job SET thumbnail = ?, thumbnail_type = ?, updated_at = NOW() WHERE id = ?", bytes, contentType, id);
    }

    public void clearThumbnail(UUID id) {
        jdbc.update("UPDATE youtube_publish_job SET thumbnail = NULL, thumbnail_type = NULL WHERE id = ?", id);
    }

    public void noteError(UUID id, String error) {
        jdbc.update("UPDATE youtube_publish_job SET last_error = ?, updated_at = NOW() WHERE id = ?", abbreviate(error), id);
    }

    public void updateMetadata(UUID id, String title, String description, String tags, Privacy privacy, Instant publishAt,
                               Instant publicConfirmedAt, String youtubePrivacy, Status status) {
        jdbc.update("""
                UPDATE youtube_publish_job SET title = ?, description = ?, tags = ?, privacy = ?, publish_at = ?,
                    public_confirmed_at = COALESCE(?, public_confirmed_at), youtube_privacy = ?, status = ?, updated_at = NOW()
                WHERE id = ?""", title, description, tags, privacy.name(), ts(publishAt), ts(publicConfirmedAt), youtubePrivacy,
                status.name(), id);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
