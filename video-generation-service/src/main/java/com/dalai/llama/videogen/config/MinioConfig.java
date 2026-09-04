package com.dalai.llama.videogen.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MinioConfig {

    /** Real object I/O -- every {@code putObject}/{@code getObject}/{@code statObject}/
     * {@code uploadObject} call in this service uses this one, unqualified, against the
     * cluster-internal endpoint (fast, no round-trip through a public ingress). */
    @Bean
    @Primary
    public MinioClient minioClient(
            @Value("${video-gen.minio.endpoint}") String endpoint,
            @Value("${video-gen.minio.access-key}") String accessKey,
            @Value("${video-gen.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** A presigned URL is signed against whatever endpoint the client was built with -- the
     * internal-only {@code minio.infra.svc.cluster.local} hostname can't be swapped into the
     * returned URL after the fact without invalidating the AWS SigV4 signature (the Host header
     * is part of what's signed). This client exists only to presign, against the real public
     * MinIO route (see {@code MINIO_PUBLIC_URL} / {@code media.dalaillama.in}) -- URLs from it
     * are what get handed to fal.ai as reference/source URLs and to browsers as
     * {@code <video src="...">}. Blank {@code public-url} falls back to the internal endpoint
     * (local/dev, where there's no public route). Mirrors {@code
     * pre-production-service.MinioConfig.publicMinioClient} verbatim, including the region-set
     * trick that skips getBucketLocation's real network round-trip against the public URL
     * (which resolves to 127.0.0.1 inside the cluster, connection refused). */
    @Bean
    public MinioClient publicMinioClient(
            @Value("${video-gen.minio.endpoint}") String endpoint,
            @Value("${video-gen.minio.public-url:}") String publicUrl,
            @Value("${video-gen.minio.access-key}") String accessKey,
            @Value("${video-gen.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl)
                .credentials(accessKey, secretKey)
                .region("us-east-1")
                .build();
    }
}
