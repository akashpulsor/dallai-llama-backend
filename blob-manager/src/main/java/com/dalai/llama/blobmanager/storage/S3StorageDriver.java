package com.dalai.llama.blobmanager.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public class S3StorageDriver implements StorageDriver {

    private final S3Client s3;
    private final String bucket;
    private final String endpoint;

    public S3StorageDriver(Map<String,String> props) {
        this.endpoint = props.getOrDefault("endpoint","http://minio:9000");
        this.bucket = props.getOrDefault("bucket","pbx-recordings");

        AwsBasicCredentials cred = AwsBasicCredentials.create(
                props.getOrDefault("accessKey","admin"),
                props.getOrDefault("secretKey","admin123")
        );

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(cred))
                .httpClientBuilder(ApacheHttpClient.builder())
                .region(Region.US_EAST_1)
                .build();
    }

    @Override
    public String upload(String tenantId, String localPath) throws Exception {
        Path p = Path.of(localPath);
        String objectId = UUID.randomUUID().toString();
        String key = tenantId + "/" + objectId + "/" + p.getFileName().toString();

        PutObjectRequest por = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3.putObject(por, RequestBody.fromFile(p));

        String url = endpoint + "/" + bucket + "/" + key;
        return String.format("{\"url\":\"%s\",\"objectId\":\"%s\"}", url, objectId);
    }

    @Override
    public boolean delete(String tenantId, String objectId) throws Exception {
        String prefix = tenantId + "/" + objectId + "/";

        ListObjectsV2Request lor = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();

        ListObjectsV2Response resp = s3.listObjectsV2(lor);
        boolean ok = true;

        for (S3Object obj : resp.contents()) {
            DeleteObjectRequest dor = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(obj.key())
                    .build();
            s3.deleteObject(dor);
        }
        return ok;
    }
}
