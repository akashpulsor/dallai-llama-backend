package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FounderAvatarPreviewServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void approvesPortraitAvatarQualityTestAsReusableAvatar() {
        CreatorScriptRepository scripts = mock(CreatorScriptRepository.class);
        CreatorAssetRepository assets = mock(CreatorAssetRepository.class);
        AssetStorageService storage = mock(AssetStorageService.class);
        CreatorAiService ai = mock(CreatorAiService.class);
        UUID scriptId = UUID.randomUUID();
        Map<String, Object> testAsset = new LinkedHashMap<>(Map.of(
                "bucket", "creator-assets",
                "objectKey", "founders/avatar-test.mp4",
                "contentType", "video/mp4",
                "assetUrl", "https://minio/avatar-test.mp4"
        ));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        profile.put("voiceApprovalStatus", "APPROVED");
        profile.put("avatarPreviewStatus", "NOT_REQUESTED");
        profile.put("avatarTestStatus", "TEST_READY");
        profile.put("avatarTestAsset", testAsset);
        profile.put("avatarTestUrl", "https://minio/avatar-test.mp4");
        profile.put("avatarTestLocalModels", Map.of(
                "voiceModel", "client_rvc_english",
                "talkingAvatarModel", "fal_happy_horse_v1_1",
                "lipSyncModel", "fal_latentsync",
                "avatarResolution", "1080p"
        ));
        profile.put("sourceAsset", Map.of(
                "bucket", "creator-assets",
                "objectKey", "founders/source.mp4"
        ));
        CreatorScript script = CreatorScript.builder()
                .id(scriptId)
                .tenantId("tenant")
                .userId("user")
                .title("Portrait avatar")
                .durationSeconds(30)
                .scriptPayload(new LinkedHashMap<>(Map.of("founderAvatarProfile", profile)))
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(scripts.findByIdAndTenantIdAndUserId(scriptId, "tenant", "user")).thenReturn(Optional.of(script));
        when(scripts.saveAndFlush(any(CreatorScript.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FounderAvatarPreviewService service = new FounderAvatarPreviewService(
                scripts,
                assets,
                storage,
                ai,
                new ObjectMapper(),
                WebClient.builder(),
                "http://127.0.0.1:1",
                120000
        );

        Map<String, Object> approved = service.updateApproval(
                scriptId,
                Map.of("decision", "APPROVE"),
                "tenant",
                "user"
        );
        Map<String, Object> approvedProfile = (Map<String, Object>) approved.get("founderAvatarProfile");
        Map<String, Object> approvedModels = (Map<String, Object>) approvedProfile.get("localModels");
        assertEquals("APPROVED", approved.get("status"));
        assertEquals("APPROVED", approvedProfile.get("avatarPreviewStatus"));
        assertEquals("APPROVED", approvedProfile.get("avatarTestStatus"));
        assertEquals("portrait_avatar_quality_test", approvedProfile.get("avatarPreviewSource"));
        assertEquals(testAsset, approvedProfile.get("avatarPreviewAsset"));
        assertEquals("fal_happy_horse_v1_1", approvedModels.get("talkingAvatarModel"));
        assertTrue(approvedProfile.containsKey("avatarPreviewApprovedAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsStoredVideoAndVoiceToFalPreviewThenRequiresApproval() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>(new byte[0]);
        AtomicReference<byte[]> storedPreview = new AtomicReference<>(new byte[0]);
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creator/avatar/scenes/files", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            Map<String, Object> metadata = Map.of(
                    "status", "COMPLETED",
                    "provider", "fal.ai",
                    "lipSyncModel", "fal_latentsync",
                    "lipSyncStatus", "completed",
                    "falRequestId", "fal-request-123",
                    "costMetadata", Map.of(
                            "modelApiInteracted", true,
                            "provider", "fal.ai",
                            "model", "fal_latentsync",
                            "actualTotalCost", 0,
                            "totalCost", 0
                    )
            );
            String encodedMetadata = Base64.getUrlEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(metadata)
            );
            byte[] response = "video".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("X-Dalai-Avatar-Metadata", encodedMetadata);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CreatorScriptRepository scripts = mock(CreatorScriptRepository.class);
        CreatorAssetRepository assets = mock(CreatorAssetRepository.class);
        AssetStorageService storage = mock(AssetStorageService.class);
        CreatorAiService ai = mock(CreatorAiService.class);
        UUID scriptId = UUID.randomUUID();
        Map<String, Object> sourceAsset = new LinkedHashMap<>();
        sourceAsset.put("bucket", "creator-assets");
        sourceAsset.put("objectKey", "founders/source.mp4");
        sourceAsset.put("contentType", "video/mp4");
        sourceAsset.put("originalFilename", "founder.mp4");
        Map<String, Object> voiceAsset = new LinkedHashMap<>();
        voiceAsset.put("bucket", "creator-assets");
        voiceAsset.put("objectKey", "founders/voice.wav");
        voiceAsset.put("contentType", "audio/wav");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        profile.put("voiceApprovalStatus", "APPROVED");
        profile.put("avatarPreviewStatus", "NOT_REQUESTED");
        profile.put("voicePreviewText", "This is the approved founder voice preview.");
        profile.put("sourceAsset", sourceAsset);
        profile.put("voicePreviewAsset", voiceAsset);
        profile.put("localModels", Map.of(
                "voiceModel", "client_rvc_english",
                "talkingAvatarModel", "source_video",
                "lipSyncModel", "fal_latentsync"
        ));
        CreatorScript script = CreatorScript.builder()
                .id(scriptId)
                .tenantId("tenant")
                .userId("user")
                .title("Founder video")
                .durationSeconds(30)
                .scriptPayload(new LinkedHashMap<>(Map.of("founderAvatarProfile", profile)))
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(scripts.findByIdAndTenantIdAndUserId(scriptId, "tenant", "user")).thenReturn(Optional.of(script));
        when(scripts.saveAndFlush(any(CreatorScript.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        when(storage.openObjectStream("creator-assets", "founders/source.mp4")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("source-video".getBytes(StandardCharsets.UTF_8)),
                        "video/mp4",
                        12
                )
        );
        when(storage.openObjectStream("creator-assets", "founders/voice.wav")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("voice-audio".getBytes(StandardCharsets.UTF_8)),
                        "audio/wav",
                        11
                )
        );
        when(storage.uploadCreatorAssetFromPath(anyString(), any(Path.class), eq("video/mp4"), any()))
                .thenAnswer(invocation -> {
                    Path streamedPath = invocation.getArgument(1);
                    storedPreview.set(Files.readAllBytes(streamedPath));
                    return new AssetStorageService.StoredObject(
                            "creator-assets",
                            "founders/avatar-preview.mp4",
                            "video/mp4",
                            Files.size(streamedPath),
                            "https://minio/avatar-preview.mp4"
                    );
                });
        when(assets.saveAndFlush(any(CreatorAsset.class))).thenAnswer(invocation -> {
            CreatorAsset asset = invocation.getArgument(0);
            asset.setId(UUID.randomUUID());
            return asset;
        });

        FounderAvatarPreviewService service = new FounderAvatarPreviewService(
                scripts,
                assets,
                storage,
                ai,
                objectMapper,
                WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                120000
        );
        try {
            Map<String, Object> generated = service.generatePreview(scriptId, Map.of(), "tenant", "user");
            Map<String, Object> generatedProfile = (Map<String, Object>) generated.get("founderAvatarProfile");
            assertEquals("PREVIEW_READY", generated.get("status"));
            assertEquals("PREVIEW_READY", generatedProfile.get("avatarPreviewStatus"));
            assertEquals("https://minio/avatar-preview.mp4", generatedProfile.get("avatarPreviewUrl"));
            String multipart = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("source-video"));
            assertTrue(multipart.contains("voice-audio"));
            assertTrue(multipart.contains("fal_latentsync"));
            assertTrue(multipart.contains("source_video"));
            assertEquals("video", new String(storedPreview.get(), StandardCharsets.UTF_8));
            verify(storage).openObjectStream("creator-assets", "founders/source.mp4");
            verify(storage).openObjectStream("creator-assets", "founders/voice.wav");
            verify(storage).uploadCreatorAssetFromPath(anyString(), any(Path.class), eq("video/mp4"), any());

            Map<String, Object> approved = service.updateApproval(
                    scriptId,
                    Map.of("decision", "APPROVE"),
                    "tenant",
                    "user"
            );
            Map<String, Object> approvedProfile = (Map<String, Object>) approved.get("founderAvatarProfile");
            assertEquals("APPROVED", approved.get("status"));
            assertEquals("APPROVED", approvedProfile.get("avatarPreviewStatus"));
            assertTrue(approvedProfile.containsKey("avatarPreviewApprovedAt"));
        } finally {
            server.stop(0);
        }
    }
}
