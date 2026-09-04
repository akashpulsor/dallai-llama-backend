package com.dalai.llama.postprod.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MinioConfig {

    /** Real object I/O -- every {@code putObject}/{@code getObject}/{@code statObject} call in
     * this service uses this one, against the cluster-internal endpoint. */
    @Bean
    @Primary
    public MinioClient minioClient(
            @Value("${post-production.minio.endpoint}") String endpoint,
            @Value("${post-production.minio.access-key}") String accessKey,
            @Value("${post-production.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** Presigned URLs handed to fal.ai (dubbing/lip-sync/voice-clone/upscale source URLs) and
     * to browsers (dubbed-video playback) sign against this endpoint -- must be a route
     * reachable from OUTSIDE the cluster (the internal {@code minio.infra.svc.cluster.local}
     * hostname would 502 there). Signature includes the Host header so the endpoint can't be
     * swapped after signing. Mirrors {@code pre-production-service.MinioConfig.publicMinioClient}
     * verbatim, including the region-set trick that skips getBucketLocation's real network
     * round-trip against the public URL (resolves to 127.0.0.1 inside the cluster). */
    @Bean
    public MinioClient publicMinioClient(
            @Value("${post-production.minio.endpoint}") String endpoint,
            @Value("${post-production.minio.public-url:}") String publicUrl,
            @Value("${post-production.minio.access-key}") String accessKey,
            @Value("${post-production.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl)
                .credentials(accessKey, secretKey)
                .region("us-east-1")
                .build();
    }
}
