package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastMediaKind;
import com.dalai.llama.preprod.dto.CastMediaUploadView;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Stores a cast profile's raw face image or voice sample straight to MinIO -- same client
 * StoryboardImageService already uses, no vision/clone processing here, just the reference the
 * rest of the cast flow (and eventually video-generation-service) reads from. */
@Service
public class CastMediaUploadService {

    private final MinioClient minioClient;
    private final String bucket;
    private final String castMediaPrefix;

    public CastMediaUploadService(
            MinioClient minioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.cast-media-prefix}") String castMediaPrefix
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.castMediaPrefix = castMediaPrefix;
    }

    public CastMediaUploadView upload(CastMediaKind kind, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PreProductionException.badRequest("No file was uploaded");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String extension = extensionFor(file.getOriginalFilename(), contentType);
        String objectKey = "%s/%s/%s.%s".formatted(castMediaPrefix, kind.name().toLowerCase(), UUID.randomUUID(), extension);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload cast media to MinIO: " + ex.getMessage());
        }
        return new CastMediaUploadView(bucket, objectKey);
    }

    private String extensionFor(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        }
        int slash = contentType.indexOf('/');
        return slash < 0 ? "bin" : contentType.substring(slash + 1);
    }
}
