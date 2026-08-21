package com.dalai.llama.postprod.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Streams a user-uploaded video straight to MinIO -- never reads it into a byte[] first. The
 * MinIO client itself chunks a stream of unknown/large size internally (multipart upload under
 * the hood), so this scales to whatever maxUploadSizeMb allows without a matching JVM heap spike. */
@Service
public class MinioSourceUploadService implements SourceUploadService {

    private final MinioClient minioClient;
    private final String bucket;
    private final String prefix;
    private final long maxUploadSizeBytes;

    public MinioSourceUploadService(
            MinioClient minioClient,
            @Value("${post-production.minio.bucket}") String bucket,
            @Value("${post-production.dubbing.source-prefix}") String sourcePrefix,
            @Value("${post-production.dubbing.max-upload-size-mb}") long maxUploadSizeMb
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.prefix = sourcePrefix;
        this.maxUploadSizeBytes = maxUploadSizeMb * 1024 * 1024;
    }

    @Override
    public AssetPersistenceService.PersistedAsset upload(UUID dubbingJobId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PostProductionException.badRequest("No video file uploaded");
        }
        if (file.getSize() > maxUploadSizeBytes) {
            throw PostProductionException.badRequest(
                    "Uploaded video (%d MB) exceeds the maximum of %d MB"
                            .formatted(file.getSize() / (1024 * 1024), maxUploadSizeBytes / (1024 * 1024)));
        }
        String extension = extensionFor(file.getOriginalFilename());
        String objectKey = "%s/%s%s".formatted(prefix, dubbingJobId, extension);
        try (var inputStream = file.getInputStream()) {
            // -1 unknown-parts-size sentinel isn't needed here -- MultipartFile.getSize() gives
            // MinIO the real length up front, so it uploads in one pass rather than guessing part
            // boundaries.
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType() != null ? file.getContentType() : "video/mp4")
                    .build());
        } catch (Exception ex) {
            throw PostProductionException.upstream("Could not upload source video for dubbing_job_id=" + dubbingJobId + ": " + ex.getMessage());
        }
        return new AssetPersistenceService.PersistedAsset(bucket, objectKey);
    }

    private String extensionFor(String originalFilename) {
        if (originalFilename == null) {
            return ".mp4";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 ? ".mp4" : originalFilename.substring(dot);
    }
}
