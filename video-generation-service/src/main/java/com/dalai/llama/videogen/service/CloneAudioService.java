package com.dalai.llama.videogen.service;

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
    private final String bucket;

    public CloneAudioService(JdbcTemplate jdbc, MinioClient minio, VideoAssetPersistenceService assets,
                             @Value("${video-gen.minio.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.assets = assets;
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
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUri.substring(separator + 1));
            if (bytes.length == 0) throw new IllegalArgumentException("Empty audio");
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .contentType(contentType).stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not save cloned audio", ex);
        }
        // Stable resource key replaces the previous preview; each object gets a fresh key so
        // browser caches cannot replay a previous generation after recloning.
        jdbc.update("""
                INSERT INTO cloned_voice_audio
                  (tenant_id, project_id, resource_id, shot_id, beat_id, dialogue_text, mode,
                   provider_voice_id, bucket, object_key, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, project_id, resource_id) DO UPDATE SET
                  dialogue_text = EXCLUDED.dialogue_text, mode = EXCLUDED.mode,
                  provider_voice_id = EXCLUDED.provider_voice_id, bucket = EXCLUDED.bucket,
                  object_key = EXCLUDED.object_key, updated_at = CURRENT_TIMESTAMP
                """, tenantId, projectId, beatId == null ? shotId : beatId, shotId, beatId,
                text, mode, voiceId, bucket, key);
        return assets.presignedUrl(bucket, key);
    }

    public List<CloneAudioView> list(UUID tenantId, UUID projectId) {
        return jdbc.query("""
                SELECT shot_id, beat_id, dialogue_text, mode, provider_voice_id, bucket, object_key
                FROM cloned_voice_audio WHERE tenant_id = ? AND project_id = ?
                ORDER BY updated_at, resource_id
                """, (rs, row) -> new CloneAudioView(rs.getObject("shot_id", UUID.class),
                rs.getObject("beat_id", UUID.class), rs.getString("dialogue_text"), rs.getString("mode"),
                rs.getString("provider_voice_id"), assets.presignedUrl(rs.getString("bucket"), rs.getString("object_key"))),
                tenantId, projectId);
    }

    public record CloneAudioView(UUID shotId, UUID beatId, String text, String mode,
                                 String providerVoiceId, String audioUrl) {}
}
