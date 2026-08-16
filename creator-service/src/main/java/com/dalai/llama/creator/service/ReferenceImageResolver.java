package com.dalai.llama.creator.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's product/canonical/generated reference-image resolution
 * cluster - previously the plan's "ReferenceImageResolutionService" row. Heavily used by
 * ProviderRequestBuilder (11 call sites through owner.*) and by InitialRunAssembler
 * (productReferenceImageUrls/productReferenceImageAssets) - both keep calling through owner
 * unchanged, since ScreenplayVideoService's package-private methods of the same name become
 * one-line delegations to this class instead of the implementation itself.
 *
 * <p>Like CastCharacterResolver and VideoGenerationBiller, needs no owner back-reference or Spring
 * beans at all - pure Map/List wrangling across a dozen possible field-name spellings for "where
 * did this reference image come from."
 */
final class ReferenceImageResolver {

    List<String> referenceImageUrlsForScene(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        List<String> urls = new ArrayList<>();
        addProductReferenceImageUrls(urls, scene == null ? null : scene.get("productImageUrl"));
        addProductReferenceImageUrls(urls, scene == null ? null : scene.get("generatedProductImageUrl"));
        addProductReferenceImageUrls(urls, request == null ? null : request.get("productImageUrl"));
        addProductReferenceImageUrls(urls, request == null ? null : request.get("productImageUrls"));
        addProductReferenceImageUrls(urls, contextPayload == null ? null : contextPayload.get("productImageUrl"));
        addProductReferenceImageUrls(urls, contextPayload == null ? null : contextPayload.get("productImageUrls"));
        addProductReferenceImageUrls(urls, contextPayload == null ? null : contextPayload.get("referenceImageUrls"));
        firstList(scene == null ? null : scene.get("productImageAssets"), scene == null ? null : scene.get("generatedProductImageAssets"))
                .forEach(value -> addProductReferenceImageUrl(urls, value));
        return urls;
    }

    List<String> productReferenceImageUrls(
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext
    ) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Map<String, Object> safePayload = scriptPayload == null ? Map.of() : scriptPayload;
        Map<String, Object> safeCreatorContext = creatorContext == null ? Map.of() : creatorContext;
        Map<String, Object> metadata = firstMap(safeCreatorContext.get("metadata"));
        Map<String, Object> productBrief = firstMap(
                safeCreatorContext.get("productIntelligenceBrief"),
                safePayload.get("productIntelligenceBrief")
        );
        Map<String, Object> productUnderstanding = firstMap(productBrief.get("productUnderstanding"));
        List<String> urls = new ArrayList<>();
        addProductReferenceImageUrls(urls, safeRequest.get("canonicalProductImageUrls"));
        addProductReferenceImageUrls(urls, safeRequest.get("productImageUrls"));
        addProductReferenceImageUrls(urls, safeRequest.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, metadata.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, safeCreatorContext.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("sourceProductImageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("imageUrls"));
        addProductReferenceImageUrls(urls, productBrief.get("productImageUrls"));
        addProductReferenceImageUrls(urls, productUnderstanding.get("imageUrls"));
        addDirectProductReferenceImageUrl(urls, productBrief.get("sourceUrl"));
        addDirectProductReferenceImageUrl(urls, productUnderstanding.get("sourceUrl"));
        addProductReferenceImageUrls(urls, safePayload.get("referenceImageUrls"));
        addProductReferenceImageUrls(urls, safePayload.get("productImageUrls"));
        return urls.stream().limit(8).toList();
    }

    List<Map<String, Object>> productReferenceImageAssets(
            Map<String, Object> request,
            Map<String, Object> scriptPayload,
            Map<String, Object> creatorContext
    ) {
        List<Map<String, Object>> assets = new ArrayList<>();
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        Map<String, Object> safePayload = scriptPayload == null ? Map.of() : scriptPayload;
        Map<String, Object> safeCreatorContext = creatorContext == null ? Map.of() : creatorContext;
        Map<String, Object> productBrief = firstMap(
                safeCreatorContext.get("productIntelligenceBrief"),
                safePayload.get("productIntelligenceBrief")
        );
        addProductImageAssets(assets, safeRequest.get("canonicalProductImageAssets"));
        addProductImageAssets(assets, safeRequest.get("productImageAssets"));
        addProductImageAssets(assets, safeRequest.get("referenceImageAssets"));
        addProductImageAssets(assets, safeRequest.get("referenceAssets"));
        addProductImageAssets(assets, safePayload.get("productImageAssets"));
        addProductImageAssets(assets, safePayload.get("referenceImageAssets"));
        addProductImageAssets(assets, safeCreatorContext.get("productImageAssets"));
        addProductImageAssets(assets, safeCreatorContext.get("referenceImageAssets"));
        addProductImageAssets(assets, productBrief.get("imageAssets"));
        addProductImageAssets(assets, productBrief.get("productImageAssets"));
        addProductImageAssets(assets, productBrief.get("referenceImageAssets"));
        return assets.stream()
                .limit(8)
                .map(asset -> {
                    Map<String, Object> canonical = new LinkedHashMap<>(asset);
                    canonical.put("referenceRole", "canonical_product_reference");
                    canonical.put("assetRole", "canonical_product_reference");
                    return canonical;
                })
                .toList();
    }

    Map<String, Object> enrichedVideoConsistencyBible(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        Map<String, Object> bible = new LinkedHashMap<>(firstMap(contextPayload == null ? null : contextPayload.get("videoConsistencyBible")));
        bible.putAll(firstMap(request == null ? null : request.get("videoConsistencyBible")));
        String referenceDetails = firstText(
                scene == null ? null : scene.get("referenceImageDetails"),
                request == null ? null : request.get("referenceImageDetails"),
                request == null ? null : request.get("productReferenceDetails"),
                contextPayload == null ? null : contextPayload.get("referenceImageDetails")
        );
        List<Map<String, Object>> productAssets = productImageAssetsForScene(scene, request, contextPayload);
        List<String> referenceUrls = referenceImageUrlsForScene(scene, request, contextPayload);
        putIfBlank(bible, "characterIdentityLock", "Preserve the same person across every shot: face shape, age, skin tone, eye shape, brows, nose, lips, jawline, body type, posture, and performance energy.");
        putIfBlank(bible, "hairLookLock", "Hair must remain consistent in every scene: same length, color, parting, volume, texture, flyaways, styling products, and hairline. Do not change hairstyle between shots.");
        putIfBlank(bible, "wardrobeLock", "Wardrobe, accessories, makeup, grooming, and product-facing hands must stay consistent unless the scene explicitly says otherwise.");
        putIfBlank(bible, "backgroundSetLock", "Preserve the same background geography: room layout, walls, furniture, product placement, horizon line, window/door positions, counter surfaces, lighting direction, and depth.");
        putIfBlank(bible, "lightingColorLock", "Keep lighting, exposure, contrast, color temperature, grade, and shadow direction consistent across the storyboard sequence.");
        putIfBlank(bible, "cameraLanguageLock", "Maintain consistent lens feel, framing rules, camera height, screen direction, and movement style across adjacent scenes.");
        putIfBlank(bible, "dialogueCoverageLock", "When native audio is generated, speak every scripted dialogue word in order. Do not paraphrase, skip, summarize, or replace the line.");
        putIfBlank(bible, "negativePrompt", "no face drift, no hairstyle change, no hair length change, no wardrobe change, no background layout change, no random new actor, no changed product packaging, no unreadable text, no watermark, no random subtitles, no extra limbs");
        if (!referenceDetails.isBlank()) {
            bible.put("referenceImageDetails", referenceDetails);
            bible.put("productReferenceLock", referenceDetails);
        }
        if (!productAssets.isEmpty()) {
            bible.put("productImageAssets", productAssets);
            bible.put("referenceImageAssets", productAssets);
        }
        if (!referenceUrls.isEmpty()) {
            bible.put("referenceImageUrls", referenceUrls);
        }
        bible.put("continuityPriority", List.of(
                "same_hair_look",
                "same_face_and_character_identity",
                "same_wardrobe_and_grooming",
                "same_background_set_geography",
                "same_lighting_color_camera_language",
                "exact_dialogue_coverage"
        ));
        return bible;
    }

    private void addProductReferenceImageUrls(List<String> urls, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addProductReferenceImageUrls(urls, item));
            return;
        }
        addReferenceImageUrl(urls, value);
    }

    private void addDirectProductReferenceImageUrl(List<String> urls, Object value) {
        String url = firstText(value);
        if (isProductReferenceImageUrl(url) && !urls.contains(url)) {
            urls.add(url);
        }
    }

    private boolean isProductReferenceImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            String path = java.net.URI.create(value).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).matches(".*\\.(avif|gif|jpe?g|png|webp)$");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    List<Map<String, Object>> generatedProductSceneImageAssets(
            Map<String, Object> scene,
            Map<String, Object> request
    ) {
        List<Map<String, Object>> assets = new ArrayList<>();
        addGeneratedProductSceneImageAssets(assets, request == null ? null : request.get("generatedProductImageAssets"));
        addGeneratedProductSceneImageAssets(assets, request == null ? null : request.get("seedanceReferenceImageAssets"));
        addGeneratedProductSceneImageAssets(assets, scene == null ? null : scene.get("productionImage"));
        addGeneratedProductSceneImageAssets(assets, scene == null ? null : scene.get("generatedProductImage"));
        addGeneratedProductSceneImageAssets(assets, scene == null ? null : scene.get("generatedProductImageAssets"));
        addGeneratedProductSceneImageAssets(assets, scene == null ? null : scene.get("productImageAsset"));
        return assets;
    }

    private void addGeneratedProductSceneImageAssets(List<Map<String, Object>> target, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addGeneratedProductSceneImageAssets(target, item));
            return;
        }
        Map<String, Object> asset = mapValue(value);
        if (asset.isEmpty()) {
            return;
        }
        String role = firstText(
                asset.get("referenceRole"),
                asset.get("reference_role"),
                asset.get("assetRole"),
                asset.get("assetKind"),
                asset.get("assetType")
        ).toLowerCase(Locale.ROOT);
        if (!(role.contains("generated") || role.contains("scene_frame") || role.contains("scene_production") || role.contains("production_image"))) {
            return;
        }
        Map<String, Object> generated = new LinkedHashMap<>(asset);
        generated.put("referenceRole", "generated_product_scene_frame");
        generated.put("assetRole", "generated_product_scene_frame");
        addProductImageAssets(target, generated);
    }

    List<Map<String, Object>> canonicalProductImageAssets(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            List<Map<String, Object>> allProductAssets
    ) {
        List<Map<String, Object>> assets = new ArrayList<>();
        addProductImageAssets(assets, request == null ? null : request.get("canonicalProductImageAssets"));
        addProductImageAssets(assets, contextPayload == null ? null : contextPayload.get("productImageAssets"));
        addProductImageAssets(assets, contextPayload == null ? null : contextPayload.get("referenceImageAssets"));
        addProductImageAssets(assets, request == null ? null : request.get("productImageAssets"));
        if (allProductAssets != null) {
            allProductAssets.forEach(asset -> addProductImageAssets(assets, asset));
        }
        return assets.stream()
                .filter(asset -> !isGeneratedProductSceneImageAsset(asset))
                .limit(8)
                .map(asset -> {
                    Map<String, Object> canonical = new LinkedHashMap<>(asset);
                    canonical.put("referenceRole", "canonical_product_reference");
                    canonical.put("assetRole", "canonical_product_reference");
                    return canonical;
                })
                .toList();
    }

    private boolean isGeneratedProductSceneImageAsset(Map<String, Object> asset) {
        String role = firstText(
                asset == null ? null : asset.get("referenceRole"),
                asset == null ? null : asset.get("reference_role"),
                asset == null ? null : asset.get("assetRole"),
                asset == null ? null : asset.get("assetKind"),
                asset == null ? null : asset.get("assetType")
        ).toLowerCase(Locale.ROOT);
        return role.contains("generated") || role.contains("scene_frame") || role.contains("scene_production") || role.contains("production_image");
    }

    List<String> generatedProductSceneImageUrls(
            Map<String, Object> scene,
            Map<String, Object> request,
            List<Map<String, Object>> generatedAssets
    ) {
        List<String> urls = new ArrayList<>();
        addProductReferenceImageUrls(urls, request == null ? null : request.get("generatedProductImageUrl"));
        addProductReferenceImageUrls(urls, request == null ? null : request.get("generatedProductImageUrls"));
        addProductReferenceImageUrls(urls, scene == null ? null : scene.get("productionImageUrl"));
        addProductReferenceImageUrls(urls, scene == null ? null : scene.get("generatedProductImageUrl"));
        if (generatedAssets != null) {
            generatedAssets.forEach(asset -> addReferenceImageUrl(urls, asset));
        }
        return urls.stream().limit(1).toList();
    }

    List<String> canonicalProductImageUrls(
            Map<String, Object> request,
            Map<String, Object> contextPayload,
            List<Map<String, Object>> canonicalAssets
    ) {
        List<String> urls = new ArrayList<>();
        addProductReferenceImageUrls(urls, request == null ? null : request.get("canonicalProductImageUrls"));
        addProductReferenceImageUrls(urls, contextPayload == null ? null : contextPayload.get("productImageUrls"));
        addProductReferenceImageUrls(urls, contextPayload == null ? null : contextPayload.get("referenceImageUrls"));
        if (canonicalAssets != null) {
            canonicalAssets.forEach(asset -> addReferenceImageUrl(urls, asset));
        }
        return urls.stream().limit(8).toList();
    }

    List<Map<String, Object>> productImageAssetsForScene(
            Map<String, Object> scene,
            Map<String, Object> request,
            Map<String, Object> contextPayload
    ) {
        List<Map<String, Object>> assets = new ArrayList<>();
        addProductImageAssets(assets, scene == null ? null : scene.get("productImageAssets"));
        addProductImageAssets(assets, scene == null ? null : scene.get("generatedProductImageAssets"));
        addProductImageAssets(assets, scene == null ? null : scene.get("referenceImageAssets"));
        addProductImageAssets(assets, scene == null ? null : scene.get("referenceAssets"));
        addProductImageAssets(assets, request == null ? null : request.get("productImageAssets"));
        addProductImageAssets(assets, request == null ? null : request.get("referenceImageAssets"));
        addProductImageAssets(assets, request == null ? null : request.get("referenceAssets"));
        addProductImageAssets(assets, contextPayload == null ? null : contextPayload.get("productImageAssets"));
        addProductImageAssets(assets, contextPayload == null ? null : contextPayload.get("referenceImageAssets"));
        return assets;
    }

    void addProductImageAssets(List<Map<String, Object>> target, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addProductImageAssets(target, item));
            return;
        }
        Map<String, Object> asset = mapValue(value);
        if (asset.isEmpty()) {
            return;
        }
        String bucket = firstText(asset.get("bucket"));
        String objectKey = firstText(asset.get("objectKey"), asset.get("object_key"));
        if (bucket.isBlank() || objectKey.isBlank()) {
            return;
        }
        Map<String, Object> productAsset = new LinkedHashMap<>(asset);
        productAsset.putIfAbsent("referenceRole", "product_visual_anchor");
        boolean duplicate = target.stream().anyMatch(existing -> bucket.equals(firstText(existing.get("bucket")))
                && objectKey.equals(firstText(existing.get("objectKey"), existing.get("object_key"))));
        if (!duplicate) {
            target.add(productAsset);
        }
    }

    private void addProductReferenceImageUrl(List<String> urls, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addProductReferenceImageUrl(urls, item));
            return;
        }
        Map<String, Object> asset = mapValue(value);
        if (!asset.isEmpty()) {
            if (!isProductVisualAsset(asset)) {
                return;
            }
            addReferenceImageUrl(urls, asset);
            return;
        }
        addReferenceImageUrl(urls, value);
    }

    private boolean isProductVisualAsset(Map<String, Object> asset) {
        String role = firstText(
                asset.get("referenceRole"),
                asset.get("reference_role"),
                asset.get("assetRole"),
                asset.get("role"),
                asset.get("assetType"),
                asset.get("assetKind"),
                asset.get("kind")
        ).toLowerCase(Locale.ROOT);
        return role.contains("product") || role.contains("packshot") || role.contains("catalog") || role.contains("sku");
    }

    void addReferenceImageUrl(List<String> urls, Object value) {
        if (value instanceof Map<?, ?> map) {
            addReferenceImageUrl(urls, map.get("storyboardImageUrl"));
            addReferenceImageUrl(urls, map.get("imageUrl"));
            addReferenceImageUrl(urls, map.get("publicUrl"));
            addReferenceImageUrl(urls, map.get("signedUrl"));
            addReferenceImageUrl(urls, map.get("assetUrl"));
            addReferenceImageUrl(urls, map.get("url"));
            addReferenceImageUrl(urls, map.get("href"));
            return;
        }
        String url = firstText(value);
        if (url.isBlank() || isVideoLikeUrl(url) || urls.contains(url)) {
            return;
        }
        urls.add(url);
    }

    private boolean isVideoLikeUrl(String value) {
        String normalized = defaultString(value, "").toLowerCase(Locale.ROOT);
        return normalized.contains(".mp4") || normalized.contains(".mov") || normalized.contains(".webm") || normalized.contains("video/");
    }
}
