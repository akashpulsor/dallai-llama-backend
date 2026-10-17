package com.dalai.llama.tenant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class MinioConfig {

    @Value("${dalaillama.minio.endpoint:http://minio.infra.svc.cluster.local:9000}")
    private String endpoint;

    @Value("${dalaillama.minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${dalaillama.minio.secret-key:minioadmin}")
    private String secretKey;

    @Value("${dalaillama.minio.public-url:}")
    private String publicUrl;

    /** Signs links for browsers against the public host. Signing is offline, and the host is part
     * of the signature, so it must be the host the browser will use. */
    @Bean
    public software.amazon.awssdk.services.s3.presigner.S3Presigner publicS3Presigner() {
        return software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(URI.create(publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1) // MinIO ignores region but SDK requires it
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true) // Required for MinIO
                .build();
    }
}