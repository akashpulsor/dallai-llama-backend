package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the product/canonical/generated reference-image resolution cluster
 * moved out of ScreenplayVideoService into ReferenceImageResolver - previously 0 test coverage.
 */
class ReferenceImageResolverTest {

    private final ReferenceImageResolver resolver = new ReferenceImageResolver();

    @Test
    void addReferenceImageUrl_collectsEveryDistinctNonBlankUrlFromAllRecognizedKeysAndSkipsVideoLikeUrls() {
        // Not "first match wins" - every recognized key on the map that holds a distinct,
        // non-blank, non-video URL is added, in storyboardImageUrl/imageUrl/publicUrl/signedUrl/
        // assetUrl/url/href priority order.
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("storyboardImageUrl", "https://cdn/frame.jpg");
        asset.put("imageUrl", "https://cdn/other.jpg");

        resolver.addReferenceImageUrl(urls, asset);
        assertEquals(List.of("https://cdn/frame.jpg", "https://cdn/other.jpg"), urls);

        resolver.addReferenceImageUrl(urls, "https://cdn/clip.mp4");
        assertEquals(2, urls.size());

        resolver.addReferenceImageUrl(urls, "https://cdn/frame.jpg");
        assertEquals(2, urls.size());
    }

    @Test
    void addReferenceImageUrl_deduplicatesWhenTwoKeysHoldTheSameUrl() {
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        Map<String, Object> asset = Map.of("signedUrl", "https://cdn/signed.jpg", "url", "https://cdn/signed.jpg");
        resolver.addReferenceImageUrl(urls, asset);
        assertEquals(List.of("https://cdn/signed.jpg"), urls);
    }

    @Test
    void addProductImageAssets_requiresBucketAndObjectKeyAndDedupesByThatPair() {
        List<Map<String, Object>> target = new java.util.ArrayList<>();
        resolver.addProductImageAssets(target, Map.of("bucket", "b1", "objectKey", "k1"));
        resolver.addProductImageAssets(target, Map.of("bucket", "b1", "objectKey", "k1"));
        resolver.addProductImageAssets(target, Map.of("imageUrl", "no-bucket-or-key"));
        resolver.addProductImageAssets(target, Map.of("bucket", "b1", "objectKey", "k2"));

        assertEquals(2, target.size());
        assertEquals("product_visual_anchor", target.get(0).get("referenceRole"));
    }

    @Test
    void canonicalProductImageAssets_excludesAssetsTaggedAsGeneratedSceneFrames() {
        Map<String, Object> canonical = Map.of("bucket", "b", "objectKey", "canonical.jpg", "referenceRole", "canonical_product_reference");
        Map<String, Object> generated = Map.of("bucket", "b", "objectKey", "generated.jpg", "referenceRole", "generated_product_scene_frame");

        List<Map<String, Object>> result = resolver.canonicalProductImageAssets(
                Map.of(), Map.of(), List.of(canonical, generated)
        );

        assertEquals(1, result.size());
        assertEquals("canonical_product_reference", result.get(0).get("referenceRole"));
    }

    @Test
    void generatedProductSceneImageAssets_onlyKeepsAssetsTaggedAsGeneratedOrSceneFrame() {
        Map<String, Object> scene = Map.of(
                "generatedProductImageAssets", List.of(
                        Map.of("bucket", "b", "objectKey", "keep.jpg", "assetRole", "generated_product_scene_frame"),
                        Map.of("bucket", "b", "objectKey", "drop.jpg", "assetRole", "unrelated_asset")
                )
        );

        List<Map<String, Object>> result = resolver.generatedProductSceneImageAssets(scene, Map.of());

        assertEquals(1, result.size());
        assertEquals("keep.jpg", result.get(0).get("objectKey"));
        assertEquals("generated_product_scene_frame", result.get(0).get("referenceRole"));
    }

    @Test
    void productReferenceImageUrls_capsAtEightAndDedupesAcrossSources() {
        Map<String, Object> request = Map.of(
                "canonicalProductImageUrls", List.of("https://cdn/1.jpg", "https://cdn/2.jpg"),
                "productImageUrls", List.of("https://cdn/2.jpg", "https://cdn/3.jpg", "https://cdn/4.jpg",
                        "https://cdn/5.jpg", "https://cdn/6.jpg", "https://cdn/7.jpg", "https://cdn/8.jpg", "https://cdn/9.jpg")
        );

        List<String> urls = resolver.productReferenceImageUrls(request, Map.of(), Map.of());

        assertEquals(8, urls.size());
        assertTrue(urls.contains("https://cdn/1.jpg"));
        assertFalse(urls.contains("https://cdn/9.jpg"));
    }

    @Test
    void enrichedVideoConsistencyBible_fillsContinuityLocksOnlyWhenBlank() {
        Map<String, Object> contextPayload = Map.of(
                "videoConsistencyBible", Map.of("characterIdentityLock", "Custom identity lock already set.")
        );

        Map<String, Object> bible = resolver.enrichedVideoConsistencyBible(Map.of(), Map.of(), contextPayload);

        assertEquals("Custom identity lock already set.", bible.get("characterIdentityLock"));
        assertTrue(((String) bible.get("hairLookLock")).contains("Hair must remain consistent"));
        assertTrue(bible.containsKey("continuityPriority"));
    }
}
