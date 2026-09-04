package com.dalai.llama.preprod.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MinioConfig {

    /** Real object I/O -- every {@code putObject}/{@code getObject}/{@code statObject} call in
     * this service uses this one, unqualified, same as before this bean had a sibling. */
    @Bean
    @Primary
    public MinioClient minioClient(
            @Value("${pre-production.minio.endpoint}") String endpoint,
            @Value("${pre-production.minio.access-key}") String accessKey,
            @Value("${pre-production.minio.secret-key}") String secretKey
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
     * route (see {@code MINIO_PUBLIC_URL} / {@code media.dalaillama.in}) -- mirrors creator-service's
     * existing {@code S3Presigner} built with the same public-endpoint override. Blank public-url
     * falls back to the internal endpoint (local/dev, where there's no public route to presign
     * against).
     *
     * {@code .region(...)} matters here specifically (creator-service's AWS-SDK-based S3Presigner
     * doesn't need the equivalent because AWS's presigner never makes a network call at all): with
     * no region set, the MinIO Java SDK's {@code getPresignedObjectUrl} first does a real
     * getBucketLocation round-trip against the client's own endpoint to discover it -- which, for
     * this client, means actually dialing media.dalaillama.in from inside the cluster network,
     * where that hostname resolves to 127.0.0.1 (confirmed live: connection refused) rather than
     * the real public route a browser would reach. Setting the region explicitly (same
     * us-east-1 creator-service's S3Presigner already uses) skips that lookup entirely, making
     * presigning the same pure-local signature computation the internal client already relies on. */
    @Bean
    public MinioClient publicMinioClient(
            @Value("${pre-production.minio.endpoint}") String endpoint,
            @Value("${pre-production.minio.public-url:}") String publicUrl,
            @Value("${pre-production.minio.access-key}") String accessKey,
            @Value("${pre-production.minio.secret-key}") String secretKey
    ) {
        return MinioClient.builder()
                .endpoint(publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl)
                .credentials(accessKey, secretKey)
                .region("us-east-1")
                .build();
    }
}
