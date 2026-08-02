package com.dalai.llama.creator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenplayVideoProviderGenerationServiceTest {

    @Test
    void streamsHeygenPortraitAndApprovedCloneWithoutSeparateAvatarPreview() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>(new byte[0]);
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creator/avatar/scenes/files", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            Map<String, Object> providerMetadata = Map.of(
                    "status", "COMPLETED",
                    "provider", "fal.ai",
                    "model", "fal-ai/heygen/avatar4/image-to-video",
                    "avatarModel", "fal_heygen_avatar4",
                    "lipSyncModel", "avatar_native",
                    "falRequestId", "fal-scene-123",
                    "b64_json", "z".repeat(400),
                    "costMetadata", Map.of(
                            "modelApiInteracted", true,
                            "provider", "fal.ai",
                            "model", "fal-ai/heygen/avatar4/image-to-video",
                            "totalCost", 0.42
                    )
            );
            String encoded = Base64.getUrlEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(providerMetadata)
            );
            byte[] video = "streamed-avatar-video".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("X-Dalai-Avatar-Metadata", encoded);
            exchange.sendResponseHeaders(200, video.length);
            exchange.getResponseBody().write(video);
            exchange.close();
        });
        server.start();

        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        when(storage.openObjectStream("creator-assets", "avatars/founder.jpg")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("founder-portrait".getBytes(StandardCharsets.UTF_8)),
                        "image/jpeg",
                        16
                )
        );
        when(storage.openObjectStream("creator-assets", "voices/scene.wav")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("cloned-scene-audio".getBytes(StandardCharsets.UTF_8)),
                        "audio/wav",
                        18
                )
        );
        when(storage.uploadCreatorAssetFromPath(
                eq("screenplay-videos/run/scene-1.mp4"),
                any(Path.class),
                eq("video/mp4"),
                any(Duration.class)
        )).thenAnswer(invocation -> {
            Path path = invocation.getArgument(1);
            assertEquals("streamed-avatar-video", Files.readString(path));
            return new AssetStorageService.StoredObject(
                    "creator-assets",
                    "screenplay-videos/run/scene-1.mp4",
                    "video/mp4",
                    Files.size(path),
                    "https://minio/scene-1.mp4"
            );
        });

        ScreenplayVideoProviderGenerationService service =
                new ScreenplayVideoProviderGenerationService(
                        objectMapper,
                        WebClient.builder(),
                        storage,
                        "http://127.0.0.1:" + server.getAddress().getPort()
                );
        Map<String, Object> portraitAsset = Map.of(
                "bucket", "creator-assets",
                "objectKey", "avatars/founder.jpg",
                "contentType", "image/jpeg",
                "originalFilename", "founder.jpg"
        );
        Map<String, Object> audioAsset = Map.of(
                "bucket", "creator-assets",
                "objectKey", "voices/scene.wav",
                "contentType", "audio/wav"
        );
        Map<String, Object> localModels = Map.of(
                "voiceModel", "client_rvc_english",
                "talkingAvatarModel", "fal_heygen_avatar4",
                "lipSyncModel", "avatar_native",
                "avatarResolution", "720p"
        );
        Map<String, Object> founderProfile = new LinkedHashMap<>();
        founderProfile.put("consentConfirmed", true);
        founderProfile.put("voiceApprovalStatus", "APPROVED");
        founderProfile.put("avatarPreviewStatus", "NOT_REQUESTED");
        founderProfile.put("avatarPortraitAsset", portraitAsset);
        founderProfile.put("localModels", localModels);
        Map<String, Object> providerRequest = new LinkedHashMap<>();
        providerRequest.put("prompt", "Natural eye contact with restrained hand movement.");
        providerRequest.put("dialogueScript", "Procrastination is not a failure of willpower.");
        providerRequest.put("exactDialogue", "Procrastination is not a failure of willpower.");
        providerRequest.put("language", "English");
        providerRequest.put("languageCode", "en-IN");
        providerRequest.put("founderConsentConfirmed", true);
        providerRequest.put("founderAvatarProfile", founderProfile);
        providerRequest.put("avatarPortraitAsset", portraitAsset);
        providerRequest.put("dialogueAudioAsset", audioAsset);
        providerRequest.put("localModels", localModels);
        providerRequest.put("talkingStyle", "stable");
        providerRequest.put("expression", "Warm and reassuring");

        try {
            ScreenplayVideoProviderGenerationService.GeneratedSceneVideo generated = service.generate(
                    new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                            "run-1",
                            "script-1",
                            "scene-1",
                            1,
                            "dalai_llama",
                            "fal_heygen_avatar4",
                            "Natural eye contact with restrained hand movement.",
                            8,
                            "9:16",
                            "talking_head",
                            101,
                            202,
                            Map.of(),
                            Map.of("dialogueAudio", audioAsset),
                            providerRequest,
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            List.of(),
                            Map.of(),
                            Map.of(),
                            "screenplay-videos/run/scene-1.mp4",
                            Duration.ofDays(7)
                    )
            );
            assertNull(generated.bytes());
            assertEquals("fal-scene-123", generated.operationName());
            assertEquals("screenplay-videos/run/scene-1.mp4", generated.storedObject().objectKey());
            assertEquals("minio_stream_multipart", generated.metadata().get("mediaTransferMode"));
            assertEquals("avatar_native", generated.metadata().get("lipSyncModel"));
            assertEquals(
                    "[base64 chars=400 omitted_stored_in_object_storage]",
                    generated.providerResponse().get("b64_json")
            );
            String multipart = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("founder-portrait"));
            assertTrue(multipart.contains("cloned-scene-audio"));
            assertTrue(multipart.contains("fal_heygen_avatar4"));
            assertTrue(multipart.contains("avatar_native"));
            assertTrue(multipart.contains("720p"));
            assertTrue(multipart.contains("stable"));
            assertTrue(multipart.contains("Warm and reassuring"));
            assertTrue(multipart.contains("Procrastination is not a failure of willpower."));
            assertTrue(multipart.contains("en-IN"));
            verify(storage).openObjectStream("creator-assets", "avatars/founder.jpg");
            verify(storage).openObjectStream("creator-assets", "voices/scene.wav");
            verify(storage).uploadCreatorAssetFromPath(
                    eq("screenplay-videos/run/scene-1.mp4"),
                    any(Path.class),
                    eq("video/mp4"),
                    any(Duration.class)
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void selectsFalSeedanceTwoEndpointsForTextAndImageGeneration() {
        assertEquals(
                "bytedance/seedance-2.0/text-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-2-0", false)
        );
        assertEquals(
                "bytedance/seedance-2.0/image-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("bytedance/seedance-2.0", true)
        );
        assertEquals(
                "bytedance/seedance-2.0/fast/image-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-2-0-fast", true)
        );
        assertEquals(
                "bytedance/seedance-2.0/reference-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("bytedance/seedance-2.0", false, true)
        );
        assertEquals(
                "bytedance/seedance-2.0/fast/reference-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-2-0-fast", false, true)
        );
    }

    @Test
    void keepsLegacySeedanceSelectionsCompatibleWithFalEndpoints() {
        assertEquals(
                "fal-ai/bytedance/seedance/v1.5/pro/text-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-1-5-pro", false)
        );
        assertEquals(
                "fal-ai/bytedance/seedance/v1/pro/image-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-1-0-pro", true)
        );
        assertEquals(
                "fal-ai/bytedance/seedance/v1.5/pro/text-to-video",
                ScreenplayVideoProviderGenerationService.falSeedanceEndpoint("seedance-1-5-pro", false, true)
        );
    }

    @Test
    void normalizesFalSeedanceLimitsAndPricing() {
        assertEquals(4, ScreenplayVideoProviderGenerationService.falSeedanceDuration(2));
        assertEquals(15, ScreenplayVideoProviderGenerationService.falSeedanceDuration(20));
        assertEquals(12, ScreenplayVideoProviderGenerationService.falSeedanceDuration("seedance-v1.5-pro", 15));
        assertEquals("720p", ScreenplayVideoProviderGenerationService.falSeedanceResolution("4k"));
        assertEquals("720p", ScreenplayVideoProviderGenerationService.falSeedanceResolution("seedance-2.0-fast", "1080p"));
        assertEquals("9:16", ScreenplayVideoProviderGenerationService.falSeedanceAspectRatio("portrait"));
        assertEquals(new BigDecimal("0.3034"), ScreenplayVideoProviderGenerationService.falSeedanceRatePerSecond("seedance-2-0"));
        assertEquals(new BigDecimal("0.3024"), ScreenplayVideoProviderGenerationService.falSeedanceRatePerSecond("seedance-2-0", true));
        assertEquals(new BigDecimal("0.2419"), ScreenplayVideoProviderGenerationService.falSeedanceRatePerSecond("seedance-2-0-fast"));
        assertEquals(new BigDecimal("0.0520"), ScreenplayVideoProviderGenerationService.falSeedanceRatePerSecond("seedance-v1.5-pro"));
    }

    @Test
    void describesGeneratedFrameAndCanonicalProductRolesForReferenceVideo() {
        String prompt = ScreenplayVideoProviderGenerationService.seedanceProductReferencePrompt(
                "Slow orbit with a controlled light sweep.",
                3
        );

        assertTrue(prompt.contains("@Image1 is the approved shot-specific CGI composition"));
        assertTrue(prompt.contains("@Image2 is the original canonical product reference"));
        assertTrue(prompt.contains("@Image3"));
        assertTrue(prompt.contains("Slow orbit with a controlled light sweep."));
    }

    @Test
    void ordersGeneratedFrameBeforeCanonicalProductAcrossStoredAssets() throws Exception {
        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream output = invocation.getArgument(2);
            output.write("generated-shot-frame".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(storage).downloadObjectToOutputStream(
                eq("creator-assets"),
                eq("generated/shot-1.jpg"),
                any(java.io.OutputStream.class)
        );
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream output = invocation.getArgument(2);
            output.write("canonical-product".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(storage).downloadObjectToOutputStream(
                eq("creator-assets"),
                eq("products/source.jpg"),
                any(java.io.OutputStream.class)
        );
        ScreenplayVideoProviderGenerationService service =
                new ScreenplayVideoProviderGenerationService(
                        new ObjectMapper(),
                        WebClient.builder(),
                        storage,
                        ""
                );
        Map<String, Object> generated = Map.of(
                "bucket", "creator-assets",
                "objectKey", "generated/shot-1.jpg",
                "contentType", "image/jpeg",
                "referenceRole", "generated_product_scene_frame"
        );
        Map<String, Object> canonical = Map.of(
                "bucket", "creator-assets",
                "objectKey", "products/source.jpg",
                "contentType", "image/jpeg",
                "referenceRole", "canonical_product_reference"
        );
        Map<String, Object> providerRequest = new LinkedHashMap<>();
        providerRequest.put("seedanceReferenceToVideo", true);
        providerRequest.put("generatedProductImageAssets", List.of(generated));
        providerRequest.put("canonicalProductImageAssets", List.of(canonical));
        providerRequest.put("seedanceReferenceImageAssets", List.of(canonical, generated));
        providerRequest.put("productCreativeEvidence", Map.of(
                "ingredientsOrMaterials", List.of("green tea", "lemon"),
                "approvedClaims", List.of("no added sugar")
        ));
        providerRequest.put("productShotPlan", Map.of(
                "shotType", "Ingredient macro",
                "storyBeat", "Reveal the real ingredients behind the product."
        ));
        providerRequest.put("soundDesignPrompt", "Crisp leaf movement with a restrained transition hit.");
        providerRequest.put("audioProductionPlan", Map.of("music", "Light modern pulse under voiceover."));
        providerRequest.put("editingPlan", Map.of("cut", "Match cut from the lemon arc into the packshot."));
        Map<String, Object> scene = Map.of(
                "hook", "Open on a lemon slice crossing frame.",
                "retentionGoal", "Reveal a new proof detail before the packshot.",
                "patternInterrupt", "Macro-to-wide scale change.",
                "brollStyle", "Premium ingredient macro",
                "editingNotes", "Exit on the lemon arc for a match cut."
        );
        ScreenplayVideoProviderGenerationService.SceneVideoRequest request =
                new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                        "run-1",
                        "script-1",
                        "scene-1",
                        1,
                        "seedance",
                        "bytedance/seedance-2.0",
                        "Slow orbit.",
                        5,
                        "9:16",
                        "ai_generated",
                        101,
                        202,
                        Map.of(),
                        scene,
                        providerRequest,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of(),
                        Map.of(),
                        "screenplay-videos/run/scene-1.mp4",
                        Duration.ofDays(7)
                );

        var configMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod("providerConfig", String.class, String.class);
        configMethod.setAccessible(true);
        Object config = configMethod.invoke(service, "seedance", "bytedance/seedance-2.0");
        var requestMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildFalSeedanceRequest",
                        config.getClass(),
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        String.class,
                        int.class,
                        long.class
                );
        requestMethod.setAccessible(true);
        var promptMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildConsistencyPrompt",
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        long.class,
                        long.class,
                        String.class
                );
        promptMethod.setAccessible(true);
        String productionPrompt = (String) promptMethod.invoke(
                service,
                request,
                101L,
                202L,
                "no humans"
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) requestMethod.invoke(
                service,
                config,
                request,
                productionPrompt,
                5,
                202L
        );

        @SuppressWarnings("unchecked")
        List<String> imageUrls = (List<String>) body.get("image_urls");
        assertEquals(2, imageUrls.size());
        assertEquals(
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString("generated-shot-frame".getBytes(StandardCharsets.UTF_8)),
                imageUrls.get(0)
        );
        assertEquals(
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString("canonical-product".getBytes(StandardCharsets.UTF_8)),
                imageUrls.get(1)
        );
        assertTrue(String.valueOf(body.get("prompt")).contains("@Image1 is the approved shot-specific CGI composition"));
        assertTrue(String.valueOf(body.get("prompt")).contains("@Image2 is the original canonical product reference"));
        assertTrue(String.valueOf(body.get("prompt")).contains("green tea"));
        assertTrue(String.valueOf(body.get("prompt")).contains("Open on a lemon slice crossing frame."));
        assertTrue(String.valueOf(body.get("prompt")).contains("Reveal a new proof detail before the packshot."));
        assertTrue(String.valueOf(body.get("prompt")).contains("Premium ingredient macro"));
        assertTrue(String.valueOf(body.get("prompt")).contains("Crisp leaf movement"));
        assertTrue(String.valueOf(body.get("prompt")).contains("Match cut from the lemon arc"));
    }
}
