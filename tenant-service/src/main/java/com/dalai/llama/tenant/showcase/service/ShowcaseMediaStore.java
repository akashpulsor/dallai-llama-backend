package com.dalai.llama.tenant.showcase.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

/** Our copies of what creators upload to their profiles, in MinIO, and short-lived links to them. */
@Component
@RequiredArgsConstructor
public class ShowcaseMediaStore {

    /** Long enough to watch a film start to finish; a page reload asks for a new one. */
    static final Duration LINK_TTL = Duration.ofHours(1);

    private final S3Client s3Client;
    private final S3Presigner publicS3Presigner;

    @Value("${dalaillama.minio.showcase-bucket:creator-assets}")
    private String bucket;

    /** Streams the file in -- a film is never held in memory. */
    public void put(String key, MultipartFile file, String contentType) {
        try (InputStream in = file.getInputStream()) {
            s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String key) {
        s3Client.deleteObject(b -> b.bucket(bucket).key(key));
    }

    public String link(String key) {
        return publicS3Presigner.presignGetObject(r -> r.signatureDuration(LINK_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())).url().toString();
    }
}
