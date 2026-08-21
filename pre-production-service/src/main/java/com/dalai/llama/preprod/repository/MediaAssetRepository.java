package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findByBucketAndObjectKey(String bucket, String objectKey);
}
