package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's product/style reference-image upload flow -
 * uploadReferenceImage plus the script-attachment step it triggers. Owner-scoped (same package,
 * owner back-reference) because uploadReferenceImage needs owner.loadScript's real repository
 * validation, not because the upload/attach logic itself is entangled with the rest of the class -
 * attachReferenceImageToScript and its two append helpers were already exclusive to this flow
 * (verified: zero other callers before this move).
 */
final class ReferenceAssetUploader {

    private static final String ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE = "SCREENPLAY_REFERENCE_IMAGE";

    private final ScreenplayVideoService owner;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorScriptRepository scriptRepository;

    ReferenceAssetUploader(
            ScreenplayVideoService owner,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            CreatorScriptRepository scriptRepository
    ) {
        this.owner = owner;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.scriptRepository = scriptRepository;
    }

    Map<String, Object> uploadReferenceImage(
            UUID scriptId,
            MultipartFile file,
            String details,
            boolean enhanceScreenplay,
            String tenantId,
            String userId
    ) {
        CreatorScript script = owner.loadScript(scriptId, tenantId, userId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a product or style reference image.");
        }
        String contentType = firstText(file.getContentType(), "application/octet-stream");
        MediaContentTypeResolver contentTypeResolver = new MediaContentTypeResolver();
        if (!contentTypeResolver.isImageContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference asset must be a JPG, PNG, WebP, AVIF, or GIF image.");
        }

        String objectKey = "screenplay-videos/%s/reference-images/%s-%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                new FilenameSanitizer().safeSlug(firstText(file.getOriginalFilename(), "reference-image")),
                contentTypeResolver.imageFileExtension(contentType)
        );

        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, ScreenplayVideoService.SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded reference image.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("source", "user_upload");
        metadata.put("referenceRole", "product_visual_anchor");
        metadata.put("assetRole", "product_visual_anchor");
        metadata.put("details", firstText(details));
        metadata.put("enhanceScreenplay", enhanceScreenplay);
        metadata.put("originalFilename", firstText(file.getOriginalFilename(), "reference-image"));
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assetId", asset.getId().toString());
        response.put("id", asset.getId().toString());
        response.put("scriptId", script.getId().toString());
        response.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        response.put("assetType", ASSET_TYPE_SCREENPLAY_REFERENCE_IMAGE);
        response.put("referenceRole", "product_visual_anchor");
        response.put("assetRole", "product_visual_anchor");
        response.put("bucket", stored.bucket());
        response.put("objectKey", stored.objectKey());
        response.put("contentType", stored.contentType());
        response.put("sizeBytes", stored.sizeBytes());
        response.put("publicUrl", stored.signedUrl());
        response.put("signedUrl", stored.signedUrl());
        response.put("assetUrl", stored.signedUrl());
        response.put("details", firstText(details));
        response.put("enhanceScreenplay", enhanceScreenplay);
        response.put("metadata", metadata);
        attachReferenceImageToScript(script, response, details, enhanceScreenplay);
        response.put("scriptReferenceUpdated", true);
        return response;
    }

    private void attachReferenceImageToScript(CreatorScript script, Map<String, Object> referenceAsset, String details, boolean enhanceScreenplay) {
        if (script == null || referenceAsset == null || referenceAsset.isEmpty()) {
            return;
        }
        Map<String, Object> payload = copyMap(script.getScriptPayload());
        Map<String, Object> creatorContext = copyMap(payload.get("creatorContext"));
        Map<String, Object> metadata = copyMap(creatorContext.get("metadata"));
        String url = firstText(referenceAsset.get("signedUrl"), referenceAsset.get("publicUrl"), referenceAsset.get("assetUrl"));
        Map<String, Object> assetSummary = new LinkedHashMap<>(referenceAsset);
        assetSummary.remove("metadata");

        appendUniqueValue(payload, "referenceImageUrls", url);
        appendUniqueValue(payload, "productImageUrls", url);
        appendUniqueValue(creatorContext, "referenceImageUrls", url);
        appendUniqueValue(creatorContext, "productImageUrls", url);
        appendUniqueValue(metadata, "referenceImageUrls", url);
        appendUniqueAsset(payload, "referenceImageAssets", assetSummary);
        appendUniqueAsset(payload, "productImageAssets", assetSummary);
        appendUniqueAsset(creatorContext, "referenceImageAssets", assetSummary);
        appendUniqueAsset(creatorContext, "productImageAssets", assetSummary);

        String cleanDetails = firstText(details);
        if (!cleanDetails.isBlank()) {
            payload.put("referenceImageDetails", cleanDetails);
            creatorContext.put("referenceImageDetails", cleanDetails);
            metadata.put("referenceImageDetails", cleanDetails);
            if (enhanceScreenplay) {
                payload.put("screenplayEnhancementReferenceDetails", cleanDetails);
                creatorContext.put("screenplayEnhancementReferenceDetails", cleanDetails);
            }
        }
        payload.put("screenplayReferenceEnhancementEnabled", enhanceScreenplay);
        creatorContext.put("screenplayReferenceEnhancementEnabled", enhanceScreenplay);
        creatorContext.put("metadata", metadata);
        payload.put("creatorContext", creatorContext);
        script.setScriptPayload(payload);
        scriptRepository.saveAndFlush(script);
    }

    private void appendUniqueValue(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        List<Object> values = new ArrayList<>(firstList(target.get(key)));
        if (values.stream().noneMatch(item -> value.equals(String.valueOf(item)))) {
            values.add(value);
        }
        target.put(key, values);
    }

    private void appendUniqueAsset(Map<String, Object> target, String key, Map<String, Object> asset) {
        if (target == null || key == null || key.isBlank() || asset == null || asset.isEmpty()) {
            return;
        }
        String assetId = firstText(asset.get("assetId"), asset.get("id"));
        List<Map<String, Object>> assets = mapListValue(target.get(key));
        boolean exists = !assetId.isBlank() && assets.stream().anyMatch(item -> assetId.equals(firstText(item.get("assetId"), item.get("id"))));
        if (!exists) {
            assets.add(new LinkedHashMap<>(asset));
        }
        target.put(key, assets);
    }
}
