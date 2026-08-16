package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.ScreenplaySceneAssetType;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.dto.response.ScreenplaySceneAssetListResponse;
import com.dalai.llama.creator.dto.response.ScreenplaySceneAssetResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The normalized, queryable source of truth for screenplay video scene clips and their
 * combined render - replacing the previous approach of reading this state out of whichever
 * creator_generation_jobs row happens to have the most recent completed_at, which an
 * unrelated failure (e.g. a billing debit error after a successful merge) can silently
 * orphan. Callers in ScreenplayVideoService record scene/combined completions here in
 * addition to their existing JSONB writes; nothing here replaces or removes that JSONB path.
 */
@Service
public class CreatorScreenplaySceneAssetService {

    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);

    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;

    public CreatorScreenplaySceneAssetService(
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService
    ) {
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
    }

    public record SceneVideoUpsert(
            String tenantId,
            String userId,
            UUID scriptId,
            UUID runId,
            int shotNumber,
            String bucket,
            String objectKey,
            String contentType,
            Long sizeBytes,
            Integer durationSeconds,
            String provider,
            String status
    ) {
    }

    public record CombinedVideoUpsert(
            String tenantId,
            String userId,
            UUID scriptId,
            UUID runId,
            String bucket,
            String objectKey,
            String contentType,
            Long sizeBytes,
            Integer durationSeconds,
            String status
    ) {
    }

    @Transactional
    public void recordSceneVideo(SceneVideoUpsert upsert) {
        CreatorAsset asset = assetRepository
                .findByTenantIdAndUserIdAndRunIdAndShotNumberAndAssetType(
                        upsert.tenantId(),
                        upsert.userId(),
                        upsert.runId(),
                        upsert.shotNumber(),
                        ScreenplaySceneAssetType.SCENE_VIDEO.name()
                )
                .orElseGet(() -> CreatorAsset.builder()
                        .tenantId(upsert.tenantId())
                        .userId(upsert.userId())
                        .assetType(ScreenplaySceneAssetType.SCENE_VIDEO.name())
                        .runId(upsert.runId())
                        .shotNumber(upsert.shotNumber())
                        .build());
        asset.setScriptId(upsert.scriptId());
        asset.setBucket(upsert.bucket());
        asset.setObjectKey(upsert.objectKey());
        asset.setContentType(upsert.contentType());
        asset.setSizeBytes(upsert.sizeBytes());
        asset.setDurationSeconds(upsert.durationSeconds());
        asset.setProvider(upsert.provider());
        asset.setStatus(upsert.status());
        assetRepository.save(asset);
    }

    @Transactional
    public void recordCombinedVideo(CombinedVideoUpsert upsert) {
        CreatorAsset asset = assetRepository
                .findByTenantIdAndUserIdAndRunIdAndCombinedTrue(upsert.tenantId(), upsert.userId(), upsert.runId())
                .orElseGet(() -> CreatorAsset.builder()
                        .tenantId(upsert.tenantId())
                        .userId(upsert.userId())
                        .assetType(ScreenplaySceneAssetType.COMBINED_VIDEO.name())
                        .runId(upsert.runId())
                        .combined(true)
                        .build());
        asset.setScriptId(upsert.scriptId());
        asset.setBucket(upsert.bucket());
        asset.setObjectKey(upsert.objectKey());
        asset.setContentType(upsert.contentType());
        asset.setSizeBytes(upsert.sizeBytes());
        asset.setDurationSeconds(upsert.durationSeconds());
        asset.setStatus(upsert.status());
        assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public ScreenplaySceneAssetListResponse listSceneAssets(UUID scriptId, String tenantId, String userId) {
        List<ScreenplaySceneAssetResponse> scenes = assetRepository
                .findByTenantIdAndUserIdAndScriptIdAndAssetTypeOrderByShotNumberAsc(
                        tenantId, userId, scriptId, ScreenplaySceneAssetType.SCENE_VIDEO.name()
                )
                .stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(
                        ScreenplaySceneAssetResponse::shotNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
        ScreenplaySceneAssetResponse combinedVideo = assetRepository
                .findByTenantIdAndUserIdAndScriptIdAndAssetTypeOrderByShotNumberAsc(
                        tenantId, userId, scriptId, ScreenplaySceneAssetType.COMBINED_VIDEO.name()
                )
                .stream()
                .findFirst()
                .map(this::toResponse)
                .orElse(null);
        return new ScreenplaySceneAssetListResponse(scenes, combinedVideo);
    }

    @Transactional
    public ScreenplaySceneAssetResponse setAccepted(UUID assetId, boolean accepted, String tenantId, String userId) {
        CreatorAsset asset = assetRepository.findById(assetId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scene asset was not found."));
        asset.setAccepted(accepted);
        asset.setAcceptedAt(accepted ? OffsetDateTime.now() : null);
        asset.setAcceptedBy(accepted ? userId : null);
        return toResponse(assetRepository.save(asset));
    }

    private ScreenplaySceneAssetResponse toResponse(CreatorAsset asset) {
        String videoUrl = asset.getBucket() == null || asset.getObjectKey() == null
                ? null
                : assetStorageService.signedUrl(asset.getBucket(), asset.getObjectKey(), SIGNED_URL_TTL);
        return new ScreenplaySceneAssetResponse(
                asset.getId(),
                asset.getScriptId(),
                asset.getRunId(),
                asset.getShotNumber(),
                ScreenplaySceneAssetType.valueOf(asset.getAssetType()),
                asset.getStatus(),
                videoUrl,
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getDurationSeconds(),
                asset.getProvider(),
                asset.isAccepted(),
                asset.getAcceptedAt(),
                asset.getAcceptedBy(),
                asset.isCombined(),
                asset.getCreatedAt()
        );
    }
}
