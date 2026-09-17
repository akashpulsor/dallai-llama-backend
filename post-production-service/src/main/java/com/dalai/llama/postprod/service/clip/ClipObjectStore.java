package com.dalai.llama.postprod.service.clip;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * The only place clip bytes move between MinIO and local disk.
 *
 * <p>Every cut of a shot is produced by ffmpeg working on files, so every one of these flows needs
 * the same three steps: fetch the current clip, run a filter, put the result back. Written once
 * here so a fix to any of it -- a timeout, a signing endpoint, a size check -- is a fix everywhere,
 * rather than three copies that drift until one of them is the one with the bug.
 *
 * <p>Presigning uses the PUBLIC client. A URL signed against the in-cluster endpoint is signed for a
 * host a browser cannot reach, so it 502s outside the cluster -- the same trap the dubbing service
 * documents.
 */
@Slf4j
@Component
public class ClipObjectStore {

    /** Below this, a downloaded file is an error document rather than a video. */
    private static final long PLAUSIBLE_CLIP_BYTES = 1024;

    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final String bucket;
    private final String prefix;
    private final int signedUrlTtlSeconds;

    public ClipObjectStore(
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${post-production.minio.bucket}") String bucket,
            @Value("${post-production.minio.clip-version-prefix:shot-clip-versions}") String prefix,
            @Value("${post-production.minio.signed-url-ttl-seconds:3600}") int signedUrlTtlSeconds
    ) {
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.bucket = bucket;
        this.prefix = prefix;
        this.signedUrlTtlSeconds = signedUrlTtlSeconds;
    }

    public String bucket() {
        return bucket;
    }

    /** Where a cut of a shot lives. Keyed by shot and version so the path itself says what it is. */
    public String objectKeyFor(java.util.UUID shotId, int versionNumber) {
        return "%s/%s/v%d.mp4".formatted(prefix, shotId, versionNumber);
    }

    public void download(String sourceBucket, String objectKey, Path target) {
        try (var in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(sourceBucket).object(objectKey).build())) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw new ClipProcessingException(
                    "Could not read the clip from storage (%s): %s".formatted(objectKey, ex.getMessage()), ex);
        }
        long size = sizeOf(target);
        if (size < PLAUSIBLE_CLIP_BYTES) {
            throw new ClipProcessingException(
                    "The clip at %s is only %d bytes -- it is not a video".formatted(objectKey, size));
        }
    }

    public void upload(String objectKey, Path source) {
        try {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket).object(objectKey)
                    .filename(source.toAbsolutePath().toString())
                    .contentType("video/mp4")
                    .build());
        } catch (Exception ex) {
            throw new ClipProcessingException(
                    "Could not store the new cut (%s): %s".formatted(objectKey, ex.getMessage()), ex);
        }
    }

    public String presignedUrl(String objectBucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(objectBucket)
                    .object(objectKey)
                    .expiry(signedUrlTtlSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception ex) {
            throw new ClipProcessingException("Could not sign a URL for " + objectKey, ex);
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            return 0;
        }
    }
}
