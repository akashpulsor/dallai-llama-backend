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

class FounderAvatarTestServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void routesHeygenAvatarWithNativeLipSyncAndStoresTestVideo() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>(new byte[0]);
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creator/avatar/scenes/files", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            Map<String, Object> metadata = Map.of(
                    "status", "COMPLETED",
                    "provider", "dalai_llama",
                    "avatarModel", "fal_heygen_avatar4",
                    "lipSyncModel", "avatar_native",
                    "lipSyncStatus", "completed",
                    "falRequestId", "fal-heygen-123",
                    "avatarRequestId", "fal-heygen-123",
                    "lipSyncRequestId", "",
                    "costMetadata", Map.of(
                            "modelApiInteracted", true,
                            "provider", "fal.ai",
                            "model", "fal-ai/heygen/avatar4/image-to-video",
                            "actualTotalCost", 0.5,
                            "totalCost", 0.5
                    )
            );
            String encodedMetadata = Base64.getUrlEncoder().encodeToString(
                    objectMapper.writeValueAsBytes(metadata)
            );
            byte[] response = "heygen-avatar-video".getBytes(StandardCharsets.UTF_8);
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
        Map<String, Object> sourceAsset = new LinkedHashMap<>(Map.of(
                "bucket", "creator-assets",
                "objectKey", "founders/source.mp4",
                "contentType", "video/mp4"
        ));
        Map<String, Object> portraitAsset = new LinkedHashMap<>(Map.of(
                "bucket", "creator-assets",
                "objectKey", "founders/portrait.jpg",
                "contentType", "image/jpeg",
                "originalFilename", "portrait.jpg"
        ));
        Map<String, Object> voiceAsset = new LinkedHashMap<>(Map.of(
                "bucket", "creator-assets",
                "objectKey", "founders/voice.wav",
                "contentType", "audio/wav"
        ));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        profile.put("voiceApprovalStatus", "APPROVED");
        profile.put("voicePreviewText", "This is the approved founder voice preview.");
        profile.put("sourceAsset", sourceAsset);
        profile.put("avatarPortraitAsset", portraitAsset);
        profile.put("avatarPortraitSourceMode", "upload");
        profile.put("voicePreviewAsset", voiceAsset);
        profile.put("localModels", Map.of("voiceModel", "client_rvc_english"));
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
        when(storage.openObjectStream("creator-assets", "founders/portrait.jpg")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("portrait-image".getBytes(StandardCharsets.UTF_8)),
                        "image/jpeg",
                        14
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
                    Path path = invocation.getArgument(1);
                    return new AssetStorageService.StoredObject(
                            "creator-assets",
                            "founders/avatar-test.mp4",
                            "video/mp4",
                            Files.size(path),
                            "https://minio/avatar-test.mp4"
                    );
                });
        when(assets.saveAndFlush(any(CreatorAsset.class))).thenAnswer(invocation -> {
            CreatorAsset asset = invocation.getArgument(0);
            asset.setId(UUID.randomUUID());
            return asset;
        });

        FounderAvatarTestService service = new FounderAvatarTestService(
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
            Map<String, Object> result = service.generateTest(
                    scriptId,
                    Map.of(
                            "durationSeconds", 5,
                            "portraitMode", "upload",
                            "talkingAvatarModel", "fal_heygen_avatar4",
                            "lipSyncModel", "fal_musetalk"
                    ),
                    "tenant",
                    "user"
            );
            Map<String, Object> generatedProfile = (Map<String, Object>) result.get("founderAvatarProfile");
            assertEquals("TEST_READY", result.get("status"));
            assertEquals("TEST_READY", generatedProfile.get("avatarTestStatus"));
            assertEquals("https://minio/avatar-test.mp4", generatedProfile.get("avatarTestUrl"));
            assertEquals("fal_heygen_avatar4", generatedProfile.get("avatarTestModel"));
            assertEquals("avatar_native", generatedProfile.get("avatarTestLipSyncModel"));
            Map<String, Object> generatedModels = (Map<String, Object>) generatedProfile.get("localModels");
            assertEquals("fal_heygen_avatar4", generatedModels.get("talkingAvatarModel"));
            assertEquals("avatar_native", generatedModels.get("lipSyncModel"));
            assertEquals("720p", generatedModels.get("avatarResolution"));
            String multipart = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("portrait-image"));
            assertTrue(multipart.contains("voice-audio"));
            assertTrue(multipart.contains("fal_heygen_avatar4"));
            assertTrue(multipart.contains("avatar_native"));
            assertTrue(multipart.contains("720p"));
            verify(ai).assertWalletBalanceForModelRun(eq("FOUNDER_AVATAR_TEST"), any());
            verify(ai).publishProviderUsageDebit(
                    eq("FOUNDER_AVATAR_TEST"),
                    eq("fal.ai"),
                    eq("fal-ai/heygen/avatar4/image-to-video"),
                    any(),
                    any(),
                    anyString()
            );
        } finally {
            server.stop(0);
        }
    }
}
