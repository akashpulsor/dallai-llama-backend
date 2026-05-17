package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

@Service
public class AssetStorageService {

    private final S3Client s3Client;
    private final CreatorProperties properties;

    public AssetStorageService(S3Client s3Client, CreatorProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    public S3Client s3Client() {
        return s3Client;
    }

    public String creatorAssetsBucket() {
        return properties.getStorage().getCreatorAssetsBucket();
    }

    public String creatorExportsBucket() {
        return properties.getStorage().getCreatorExportsBucket();
    }
}
