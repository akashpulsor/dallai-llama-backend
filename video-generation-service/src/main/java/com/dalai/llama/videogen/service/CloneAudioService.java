package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.dialoguefit.AudioDurationProbe;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Durable clone previews. Only object locations are stored; playback URLs are signed on read. */
@Service
public class CloneAudioService {
    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final VideoAssetPersistenceService assets;
    private final AudioDurationProbe durationProbe;
    private final String bucket;

    public CloneAudioService(JdbcTemplate jdbc, MinioClient minio, VideoAssetPersistenceService assets,
                             AudioDurationProbe durationProbe,
                             @Value("${video-gen.minio.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.assets = assets;
        this.durationProbe = durationProbe;
        this.bucket = bucket;
    }

    public String save(UUID tenantId, UUID projectId, UUID shotId, UUID beatId,
                       String text, String mode, String voiceId, String dataUri) {
        int separator = dataUri == null ? -1 : dataUri.indexOf(",");
        if (separator < 0 || !dataUri.startsWith("data:audio/")
                || !dataUri.substring(0, separator).endsWith(";base64")) {
            throw VideoGenException.upstream("Voice synthesis did not return base64 audio");
        }
        String contentType = dataUri.substring(5, separator - 7);
        String key = "clone-audio/" + tenantId + "/" + projectId + "/" + UUID.randomUUID();
        // Measured here, where the bytes are already in hand, rather than at prepare time -- see
        // V30's comment. Unknown stays null: a missing measurement makes the fit report fall back
        // to an estimate and say so, which is better than a zero that reads as a silent take.
        Integer durationMs = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUri.substring(separator + 1));
            if (bytes.length == 0) throw new IllegalArgumentException("Empty audio");
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .contentType(contentType).stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
            double seconds = durationProbe.probeBytes(bytes, suffixFor(contentType));
            if (seconds > 0) {
                durationMs = (int) Math.round(seconds * 1000);
            }
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not save cloned audio", ex);
        }
        // Stable resource key replaces the previous preview; each object gets a fresh key so
        // browser caches cannot replay a previous generation after recloning.
        jdbc.update("""
                INSERT INTO cloned_voice_audio
                  (tenant_id, project_id, resource_id, shot_id, beat_id, dialogue_text, mode,
                   provider_voice_id, bucket, object_key, duration_ms, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, project_id, resource_id) DO UPDATE SET
                  dialogue_text = EXCLUDED.dialogue_text, mode = EXCLUDED.mode,
                  provider_voice_id = EXCLUDED.provider_voice_id, bucket = EXCLUDED.bucket,
                  object_key = EXCLUDED.object_key, duration_ms = EXCLUDED.duration_ms,
                  updated_at = CURRENT_TIMESTAMP
                """, tenantId, projectId, beatId == null ? shotId : beatId, shotId, beatId,
                text, mode, voiceId, bucket, key, durationMs);
        return assets.presignedUrl(bucket, key);
    }

    /** ffprobe picks the demuxer from the file extension when the stream itself is ambiguous, so
     * the temp file it measures is named after the content type the provider declared. */
    private String suffixFor(String contentType) {
        if (contentType == null) {
            return ".bin";
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("mpeg") || lower.contains("mp3")) return ".mp3";
        if (lower.contains("wav")) return ".wav";
        if (lower.contains("ogg")) return ".ogg";
        if (lower.contains("flac")) return ".flac";
        if (lower.contains("aac") || lower.contains("mp4") || lower.contains("m4a")) return ".m4a";
        return ".bin";
    }

    public List<CloneAudioView> list(UUID tenantId, UUID projectId) {
        return jdbc.query("""
                SELECT shot_id, beat_id, dialogue_text, mode, provider_voice_id, bucket, object_key,
                       duration_ms
                FROM cloned_voice_audio WHERE tenant_id = ? AND project_id = ?
                ORDER BY updated_at, resource_id
                """, (rs, row) -> new CloneAudioView(rs.getObject("shot_id", UUID.class),
                rs.getObject("beat_id", UUID.class), rs.getString("dialogue_text"), rs.getString("mode"),
                rs.getString("provider_voice_id"), assets.presignedUrl(rs.getString("bucket"), rs.getString("object_key")),
                (Integer) rs.getObject("duration_ms")),
                tenantId, projectId);
    }

    /**
     * Measures any row saved before duration_ms existed, and remembers the answer.
     *
     * <p>Called from the fit report rather than from a migration: the audio lives in MinIO, so
     * backfilling it in SQL is not possible, and probing every historical row at startup would
     * delay the service for takes nobody is looking at. Measuring the shots a creator actually
     * opens, once each, converges on the same place without that.
     */
    public void backfillDuration(UUID tenantId, UUID projectId, UUID resourceId, double seconds) {
        if (seconds <= 0) {
            return;
        }
        jdbc.update("""
                UPDATE cloned_voice_audio SET duration_ms = ?
                WHERE tenant_id = ? AND project_id = ? AND resource_id = ? AND duration_ms IS NULL
                """, (int) Math.round(seconds * 1000), tenantId, projectId, resourceId);
    }

    /** {@code durationMs} is null for a take saved before it was measured -- see
     * {@link #backfillDuration}. Never zero: an unmeasurable take reports nothing rather than a
     * length of nothing. */
    public record CloneAudioView(UUID shotId, UUID beatId, String text, String mode,
                                 String providerVoiceId, String audioUrl, Integer durationMs) {}
}
