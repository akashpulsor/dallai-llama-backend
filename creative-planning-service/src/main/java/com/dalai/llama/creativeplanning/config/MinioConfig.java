package com.dalai.llama.creativeplanning.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MinioConfig {

    /** Real object I/O -- every {@code putObject}/{@code getObject} call in this service uses
     * this one, unqualified, same as before this bean had a sibling. */
    @Bean
    @Primary
    public MinioClient minioClient(
            @Value("${creative-planning.minio.endpoint}") String endpoint,
            @Value("${creative-planning.minio.access-key}") String accessKey,
            @Value("${creative-planning.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** A presigned URL is signed against whatever endpoint the client was built with -- the
     * internal-only {@code minio.infra.svc.cluster.local} hostname can't be swapped into a
     * returned URL after the fact without invalidating the AWS SigV4 signature (the Host header is
     * part of what's signed). This client exists only to presign, against the real public MinIO
     * route ({@code MINIO_PUBLIC_URL} / {@code media.dalaillama.in}) -- mirrors
     * pre-production-service's identical {@code publicMinioClient}. Blank public-url falls back to
     * the internal endpoint (local/dev, where there's no public route to presign against).
     * <p>
     * {@code .region(...)} matters here: with no region set, the MinIO Java SDK's {@code
     * getPresignedObjectUrl} first does a real {@code getBucketLocation} round-trip against the
     * client's own endpoint to discover it -- for this client, that means actually dialing
     * media.dalaillama.in from inside the cluster network, where it won't resolve the way a
     * browser's request would. Setting the region explicitly (same {@code us-east-1}
     * pre-production-service uses) skips that lookup entirely. */
    @Bean
    @Qualifier("publicMinioClient")
    public MinioClient publicMinioClient(
            @Value("${creative-planning.minio.endpoint}") String endpoint,
            @Value("${creative-planning.minio.public-url:}") String publicUrl,
            @Value("${creative-planning.minio.access-key}") String accessKey,
            @Value("${creative-planning.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl)
                .credentials(accessKey, secretKey)
                .region("us-east-1")
                .build();
    }
}
