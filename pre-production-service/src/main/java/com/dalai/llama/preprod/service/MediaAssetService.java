package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.MediaAsset;
import com.dalai.llama.preprod.repository.MediaAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One shared registry for every bucket/objectKey this service references (see MediaAsset's own
 * javadoc for the DRY rationale) -- callers register a reference the first time they see it and
 * this is a no-op on every later sighting of the same (bucket, objectKey) pair. */
@Service
public class MediaAssetService {

    private final MediaAssetRepository mediaAssetRepository;

    public MediaAssetService(MediaAssetRepository mediaAssetRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Transactional
    public void registerIfAbsent(UUID tenantId, String bucket, String objectKey, MediaAssetType assetType) {
        if (mediaAssetRepository.findByBucketAndObjectKey(bucket, objectKey).isPresent()) {
            return;
        }
        mediaAssetRepository.save(MediaAsset.builder()
                .tenantId(tenantId)
                .bucket(bucket)
                .objectKey(objectKey)
                .assetType(assetType)
                .createdAt(OffsetDateTime.now())
                .build());
    }
}
