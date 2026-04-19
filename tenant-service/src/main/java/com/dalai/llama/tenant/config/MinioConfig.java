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