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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        long.class,
                        String.class
                );
        requestMethod.setAccessible(true);
        var promptMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildConsistencyPrompt",
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        long.class,
                        long.class,
                        String.class,
                        String.class
                );
        promptMethod.setAccessible(true);
        String productionPrompt = (String) promptMethod.invoke(
                service,
                request,
                101L,
                202L,
                "no humans",
                ""
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) requestMethod.invoke(
                service,
                config,
                request,
                productionPrompt,
                5,
                202L,
                ""
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

    /**
     * Characterization test for prompt-builder priority fix Deliverable A: the product/cast-face
     * reference-role announcement must survive Gemini-based prompt compression byte-for-byte,
     * the same guarantee consistencyLockText already had. Proven deterministically, not by luck
     * of where truncation happens to cut: creatorAiService is mocked to return a "compressed"
     * prompt that has clearly dropped the role text (simulating a bad/lossy Gemini rewrite), and
     * the test still asserts the role text is present verbatim in the final request body - which
     * only holds if the role text was swapped out for a protected token before ever being sent
     * to Gemini and restored afterward (spliceReferenceRoleText + compressPromptIfNeeded's
     * existing protectedContent mechanism), not because compression happened not to touch it.
     */
    @Test
    void productReferenceRoleText_survivesGeminiCompressionVerbatim_evenWhenGeminiDropsIt() throws Exception {
        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream output = invocation.getArgument(2);
            output.write("generated-shot-frame".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(storage).downloadObjectToOutputStream(eq("creator-assets"), eq("generated/shot-1.jpg"), any(java.io.OutputStream.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream output = invocation.getArgument(2);
            output.write("canonical-product".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(storage).downloadObjectToOutputStream(eq("creator-assets"), eq("products/source.jpg"), any(java.io.OutputStream.class));

        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(any(), any())).thenAnswer(invocation -> {
            Map<String, Object> vars = invocation.getArgument(1);
            return String.valueOf(vars.get("fullPrompt"));
        });
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        // Simulates a lossy Gemini rewrite that drops the reference-role announcement entirely -
        // if the fix works, this "bad" output is never allowed to reach the final prompt for the
        // protected block, because that block was swapped for a token before this call happened.
        when(creatorAiService.generate(eq("SCENE_PROMPT_COMPRESS"), any()))
                .thenReturn(Map.of("compressedPrompt", "Gemini rewrote everything and dropped the reference roles."));

        ScreenplayVideoProviderGenerationService service = new ScreenplayVideoProviderGenerationService(
                new ObjectMapper(), WebClient.builder(), storage, null, promptTemplateService, creatorAiService, ""
        );

        Map<String, Object> generated = Map.of(
                "bucket", "creator-assets", "objectKey", "generated/shot-1.jpg",
                "contentType", "image/jpeg", "referenceRole", "generated_product_scene_frame"
        );
        Map<String, Object> canonical = Map.of(
                "bucket", "creator-assets", "objectKey", "products/source.jpg",
                "contentType", "image/jpeg", "referenceRole", "canonical_product_reference"
        );
        Map<String, Object> providerRequest = new LinkedHashMap<>();
        providerRequest.put("seedanceReferenceToVideo", true);
        providerRequest.put("generatedProductImageAssets", List.of(generated));
        providerRequest.put("canonicalProductImageAssets", List.of(canonical));
        providerRequest.put("seedanceReferenceImageAssets", List.of(canonical, generated));
        Map<String, Object> scene = Map.of("hook", "Open on a lemon slice crossing frame.");
        // Padded well past SEEDANCE_PROMPT_MAX_CHARS (default 6000) so compressPromptIfNeeded
        // actually engages the Gemini-compression path instead of returning early.
        String longScenePrompt = "Slow orbit with a controlled light sweep. ".repeat(300);

        ScreenplayVideoProviderGenerationService.SceneVideoRequest request =
                new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                        "run-1", "script-1", "scene-1", 1, "seedance", "bytedance/seedance-2.0",
                        longScenePrompt, 5, "9:16", "ai_generated", 101, 202,
                        Map.of(), scene, providerRequest,
                        Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        "screenplay-videos/run/scene-1.mp4", Duration.ofDays(7)
                );

        var configMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod("providerConfig", String.class, String.class);
        configMethod.setAccessible(true);
        Object config = configMethod.invoke(service, "seedance", "bytedance/seedance-2.0");
        var promptMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildConsistencyPrompt",
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        long.class, long.class, String.class, String.class
                );
        promptMethod.setAccessible(true);
        // Must be non-blank and identical in both calls: this is the literal anchor
        // spliceReferenceRoleText looks for inside the assembled prompt to attach and protect the
        // reference-role text against. A blank consistencyLockText (as other tests in this file
        // use, since they never exceed the compression threshold) would make the splice fall back
        // to its unprotected-prepend path and defeat the point of this test.
        String consistencyLockText = "LOCK-TEXT-MARKER: preserve identity across scenes.";
        String productionPrompt = (String) promptMethod.invoke(service, request, 101L, 202L, "no humans", consistencyLockText);
        assertTrue(productionPrompt.length() > 6000, "test setup must actually exceed the compression threshold");

        var requestMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildFalSeedanceRequest",
                        config.getClass(),
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        String.class, int.class, long.class, String.class
                );
        requestMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) requestMethod.invoke(
                service, config, request, productionPrompt, 5, 202L, consistencyLockText
        );

        // The mocked "Gemini" output legitimately becomes the compressed text for the
        // non-protected portion of the prompt (that's correct compression behavior) - what this
        // test actually proves is that the reference-role text, despite never being sent to that
        // mocked call at all (swapped for a token first), still comes back verbatim afterward.
        String finalPrompt = String.valueOf(body.get("prompt"));
        assertTrue(finalPrompt.contains("Gemini rewrote everything and dropped the reference roles."),
                "sanity check: the mocked compression call actually ran");
        assertTrue(finalPrompt.contains("@Image1 is the approved shot-specific CGI composition"),
                "reference-role text must survive compression verbatim");
        assertTrue(finalPrompt.contains("@Image2 is the original canonical product reference"),
                "reference-role text must survive compression verbatim");
    }

    /**
     * Characterization tests for generatePhonemeGuideViaGemini (prompt-builder priority fix
     * Deliverable C) - this must never throw or block dialogue voice generation when the AI
     * collaborators aren't wired up, which is the state of most test/edge environments.
     */
    @Test
    void generatePhonemeGuideViaGemini_returnsEmpty_whenAiCollaboratorsUnavailable() {
        ScreenplayVideoProviderGenerationService service =
                new ScreenplayVideoProviderGenerationService(new ObjectMapper(), WebClient.builder(), mock(AssetStorageService.class), "");
        assertEquals("", service.generatePhonemeGuideViaGemini("Jaipur ki shaan, yeh kurti.", "hi-IN"));
    }

    @Test
    void generatePhonemeGuideViaGemini_returnsEmpty_whenDialogueTextBlank() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        ScreenplayVideoProviderGenerationService service = new ScreenplayVideoProviderGenerationService(
                new ObjectMapper(), WebClient.builder(), mock(AssetStorageService.class), null, promptTemplateService, creatorAiService, ""
        );
        assertEquals("", service.generatePhonemeGuideViaGemini("", "hi-IN"));
        assertEquals("", service.generatePhonemeGuideViaGemini(null, "hi-IN"));
    }

    @Test
    void generatePhonemeGuideViaGemini_returnsGuide_whenAiCollaboratorsAvailable() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(any(), any())).thenReturn("rendered");
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        when(creatorAiService.generate(eq("PHONEME_PRONUNCIATION_GUIDE"), any()))
                .thenReturn(Map.of("pronunciationGuide", "Jaipur=>JAI-pur"));
        ScreenplayVideoProviderGenerationService service = new ScreenplayVideoProviderGenerationService(
                new ObjectMapper(), WebClient.builder(), mock(AssetStorageService.class), null, promptTemplateService, creatorAiService, ""
        );
        assertEquals("Jaipur=>JAI-pur", service.generatePhonemeGuideViaGemini("Jaipur ki shaan, yeh kurti.", "hi-IN"));
    }

    @Test
    void generatePhonemeGuideViaGemini_returnsEmpty_whenAiCallThrows() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.render(any(), any())).thenReturn("rendered");
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        when(creatorAiService.generate(eq("PHONEME_PRONUNCIATION_GUIDE"), any()))
                .thenThrow(new RuntimeException("Gemini unavailable"));
        ScreenplayVideoProviderGenerationService service = new ScreenplayVideoProviderGenerationService(
                new ObjectMapper(), WebClient.builder(), mock(AssetStorageService.class), null, promptTemplateService, creatorAiService, ""
        );
        assertEquals("", service.generatePhonemeGuideViaGemini("Jaipur ki shaan, yeh kurti.", "hi-IN"));
    }

    /**
     * Characterization tests for the captionsEnabled gate (prompt-builder priority fix
     * Deliverable B) - pinned before buildConsistencyPrompt's Caption and SRT lock section
     * changes. Not wired to any provider network calls: pure reflection invocation of the
     * private prompt-template method, same pattern used throughout this file.
     */
    @Test
    void buildConsistencyPrompt_captionsEnabledAbsent_keepsExistingCaptionInstructions() throws Exception {
        String prompt = invokeBuildConsistencyPromptForCaptionTest(Map.of());
        assertTrue(prompt.contains("Use only these caption/voice cues when text or speech is visible."));
        assertFalse(prompt.contains("Do not render any on-screen captions"));
    }

    @Test
    void buildConsistencyPrompt_captionsEnabledFalse_suppressesCaptionInstructions() throws Exception {
        String prompt = invokeBuildConsistencyPromptForCaptionTest(Map.of("captionsEnabled", false));
        assertTrue(prompt.contains("Do not render any on-screen captions"));
        assertFalse(prompt.contains("Use only these caption/voice cues when text or speech is visible."));
    }

    @Test
    void buildConsistencyPrompt_captionsEnabledTrue_keepsExistingCaptionInstructions() throws Exception {
        String prompt = invokeBuildConsistencyPromptForCaptionTest(Map.of("captionsEnabled", true));
        assertTrue(prompt.contains("Use only these caption/voice cues when text or speech is visible."));
        assertFalse(prompt.contains("Do not render any on-screen captions"));
    }

    private String invokeBuildConsistencyPromptForCaptionTest(Map<String, Object> captionFields) throws Exception {
        ScreenplayVideoProviderGenerationService service =
                new ScreenplayVideoProviderGenerationService(new ObjectMapper(), WebClient.builder(), mock(AssetStorageService.class), "");
        Map<String, Object> providerRequest = new LinkedHashMap<>(captionFields);
        ScreenplayVideoProviderGenerationService.SceneVideoRequest request =
                new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                        "run-1", "script-1", "scene-1", 1, "seedance", "bytedance/seedance-2.0",
                        "Slow orbit.", 5, "9:16", "ai_generated", 101, 202,
                        Map.of(), Map.of(), providerRequest,
                        Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        "screenplay-videos/run/scene-1.mp4", Duration.ofDays(7)
                );
        var promptMethod = ScreenplayVideoProviderGenerationService.class
                .getDeclaredMethod(
                        "buildConsistencyPrompt",
                        ScreenplayVideoProviderGenerationService.SceneVideoRequest.class,
                        long.class, long.class, String.class, String.class
                );
        promptMethod.setAccessible(true);
        return (String) promptMethod.invoke(service, request, 101L, 202L, "no humans", "");
    }

    /**
     * Dry-run trace against TODAY's code using shot-1's real production data (script "The Colors
     * of Jaipur: Your New Kurti", run 8f023670-d597-4869-8d1f-a666349557c6, scene shot-1) - pulled
     * directly from the creator_db JSONB, not fabricated - to answer: does the current
     * ScreenplayVideoProviderGenerationService still fall back to a generic face reference for a
     * scene with zero cast-character matches and no continuity frame, or does it now correctly
     * prefer the run's tagged product reference image? No provider API call, no money spent - this
     * only exercises the local prompt/image-selection logic via the same reflection path
     * production's generate() uses internally.
     */
    @Test
    void jaipurKurtiShot1_noCastCharacterMatch_prefersTaggedProductReferenceOverGenericFallback() throws Exception {
        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        String realBucket = "creator-assets";
        String realObjectKey = "6c039848-bd2c-4d4c-816e-12c4fea1f7b9/b40feb97-da1e-4e0a-8db7-dca6d598eb08/product-references/1d31cb0a-697f-49d0-be9e-e84aef5d691e-image18.jpg";
        org.mockito.Mockito.doAnswer(invocation -> {
            java.io.OutputStream output = invocation.getArgument(2);
            output.write("REAL-canonical-product-reference-image18".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(storage).downloadObjectToOutputStream(eq(realBucket), eq(realObjectKey), any(java.io.OutputStream.class));

        ScreenplayVideoProviderGenerationService service =
                new ScreenplayVideoProviderGenerationService(new ObjectMapper(), WebClient.builder(), storage, "");

        // Real asset entry as stored in run.productImageAssets[0] for this run (pulled from
        // creator_generation_jobs.output_payload via kubectl exec into the postgres pod).
        Map<String, Object> canonicalProductAsset = Map.of(
                "id", "fb81b9c2-1838-4088-af44-6341d5131f42",
                "bucket", realBucket,
                "objectKey", realObjectKey,
                "contentType", "image/jpeg",
                "assetKind", "original_product_reference",
                "assetRole", "canonical_product_reference",
                "referenceRole", "canonical_product_reference",
                "assetType", "PRODUCT_REFERENCE_IMAGE"
        );

        Map<String, Object> providerRequest = new LinkedHashMap<>();
        // castCharactersForScene(scene, contextPayload) resolves to empty for shot-1: its own
        // storyboardTag has peopleInFrame=0 and sideCharacters=[] and the action text ("Close-up
        // of wooden block stamping vibrant dye onto premium cotton...") names no character - so
        // castFaceReferenceMode/castFaceImageUrls are empty exactly as they would be for a real
        // regenerate call on this scene today.
        providerRequest.put("castFaceImageUrls", List.of());
        providerRequest.put("castFaceReferenceMode", false);
        // imageLedAdPlan.enabled=false and scene.productCgiScene=null for this run (confirmed via
        // the same DB query) - so seedanceReferenceToVideo is false, same as the real Aug-16 run.
        providerRequest.put("seedanceReferenceToVideo", false);
        providerRequest.put("productImageAssets", List.of(canonicalProductAsset));
        providerRequest.put("referenceImageAssets", List.of(canonicalProductAsset));
        providerRequest.put("generatedProductImageAssets", List.of());
        // No generic "narrator" reference URL supplied here on purpose - the point of this test is
        // whether the product ASSET candidates (tried first in firstReferenceImage()) win before
        // any URL-based fallback is ever consulted.
        providerRequest.put("referenceImageUrls", List.of());
        providerRequest.put("referenceImageUrl", "");

        Map<String, Object> scene = Map.of(
                "id", "shot-1",
                "title", "Jaipur's Art Unfolds",
                "action", "Close-up of wooden block stamping vibrant dye onto premium cotton. Smooth transition to a finished, brightly colored kurti fabric flowing softly.",
                "durationSeconds", 7
        );

        ScreenplayVideoProviderGenerationService.SceneVideoRequest request =
                new ScreenplayVideoProviderGenerationService.SceneVideoRequest(
                        "8f023670-d597-4869-8d1f-a666349557c6",
                        "e7cafd4b-7fb2-4ada-88c9-6c8bc2b42d95",
                        "shot-1",
                        1,
                        "seedance",
                        "bytedance/seedance-2.0",
                        "Close-up of wooden block stamping vibrant dye onto premium cotton.",
                        7,
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
                        "screenplay-videos/run/shot-1.mp4",
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
                        long.class,
                        String.class
                );
        requestMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) requestMethod.invoke(
                service,
                config,
                request,
                "Close-up of wooden block stamping vibrant dye onto premium cotton.",
                7,
                202L,
                ""
        );

        String expectedDataUri = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString("REAL-canonical-product-reference-image18".getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedDataUri, body.get("image_url"));
        assertNull(body.get("image_urls"));
    }
}
