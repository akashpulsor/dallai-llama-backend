package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.repository.CreatorShortVideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShortSourceStageCacheService {

    private static final Logger log = LoggerFactory.getLogger(ShortSourceStageCacheService.class);
    private static final String CACHE_ROOT_KEY = "aiStageCache";

    private final CreatorShortVideoRepository videoRepository;

    public ShortSourceStageCacheService(CreatorShortVideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findStageCache(CreatorShortVideo video, CreatorAsset sourceAsset, String stageKey) {
        if (stageKey == null || stageKey.isBlank()) {
            return new LinkedHashMap<>();
        }
        String expectedSourceAssetId = sourceAssetId(video, sourceAsset);
        Map<String, Object> current = stageCache(video == null ? null : video.getMetadata(), stageKey);
        if (matchesSourceAsset(current, expectedSourceAssetId)) {
            current.put("restoredFromSourceCache", true);
            current.put("sourceCacheVideoId", video == null || video.getId() == null ? "" : video.getId().toString());
            return current;
        }

        UUID sourceAssetUuid = sourceAsset != null && sourceAsset.getId() != null
                ? sourceAsset.getId()
                : (video == null ? null : video.getSourceAssetId());
        if (sourceAssetUuid == null) {
            return new LinkedHashMap<>();
        }
        List<CreatorShortVideo> candidates = videoRepository.findTop20BySourceAssetIdOrderByUpdatedAtDesc(sourceAssetUuid);
        for (CreatorShortVideo candidate : candidates) {
            Map<String, Object> candidateCache = stageCache(candidate == null ? null : candidate.getMetadata(), stageKey);
            if (matchesSourceAsset(candidateCache, expectedSourceAssetId)) {
                candidateCache.put("restoredFromSourceCache", true);
                candidateCache.put("sourceCacheVideoId", candidate == null || candidate.getId() == null ? "" : candidate.getId().toString());
                return candidateCache;
            }
        }
        return new LinkedHashMap<>();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void putStageCache(UUID videoId, String stageKey, Map<String, Object> checkpoint) {
        if (videoId == null || stageKey == null || stageKey.isBlank() || checkpoint == null || checkpoint.isEmpty()) {
            return;
        }
        try {
            videoRepository.findById(videoId).ifPresent(video -> {
                Map<String, Object> metadata = mutableMap(video.getMetadata());
                Map<String, Object> root = mapValue(metadata.get(CACHE_ROOT_KEY));
                Map<String, Object> stored = copyPayload(checkpoint);
                stored.put("cacheKey", stageKey);
                stored.put("cacheUpdatedAt", OffsetDateTime.now().toString());
                root.put(stageKey, stored);
                metadata.put(CACHE_ROOT_KEY, root);
                video.setMetadata(metadata);
                videoRepository.save(video);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not persist source stage cache videoId={} stageKey={} message={}", videoId, stageKey, ex.getMessage());
        }
    }

    private Map<String, Object> stageCache(Map<String, Object> metadata, String stageKey) {
        Map<String, Object> root = mapValue(metadata == null ? null : metadata.get(CACHE_ROOT_KEY));
        return mapValue(root.get(stageKey));
    }

    private boolean matchesSourceAsset(Map<String, Object> checkpoint, String expectedSourceAssetId) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return false;
        }
        if (expectedSourceAssetId == null || expectedSourceAssetId.isBlank()) {
            return true;
        }
        String checkpointSourceAssetId = stringValue(checkpoint.get("sourceAssetId"), "");
        return checkpointSourceAssetId.isBlank() || expectedSourceAssetId.equals(checkpointSourceAssetId);
    }

    private String sourceAssetId(CreatorShortVideo video, CreatorAsset sourceAsset) {
        if (sourceAsset != null && sourceAsset.getId() != null) {
            return sourceAsset.getId().toString();
        }
        if (video != null && video.getSourceAssetId() != null) {
            return video.getSourceAssetId().toString();
        }
        return "";
    }

    private Map<String, Object> mutableMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> copyPayload(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((key, item) -> {
                if (key != null) {
                    copy.put(key, item);
                }
            });
        }
        return copy;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
