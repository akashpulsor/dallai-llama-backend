package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleChirpVoiceGenerationServiceMultipartTest {

    @Test
    void usesElevenLabsCharacterCostHeaderAsProviderReportedUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/text-to-speech/voice-123", exchange -> {
            byte[] response = "mp3-audio".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("character-cost", "41");
            exchange.getResponseHeaders().set("request-id", "eleven-request-41");
            exchange.getResponseHeaders().set("x-trace-id", "eleven-trace-41");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CreatorProperties properties = new CreatorProperties();
        properties.getAi().setTtsProvider("elevenlabs");
        properties.getAi().setElevenLabsApiKey("test-key");
        properties.getAi().setElevenLabsBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAi().setElevenLabsVoiceId("voice-123");
        properties.getAi().setElevenLabsUsdPerMillionChars(new BigDecimal("30"));
        GoogleChirpVoiceGenerationService service = new GoogleChirpVoiceGenerationService(
                properties,
                WebClient.builder(),
                new ObjectMapper(),
                mock(AssetStorageService.class)
        );

        try {
            GoogleChirpVoiceGenerationService.GeneratedVoice generated = service.generateVoice(
                    "Short text",
                    Map.of("provider", "elevenlabs", "voiceId", "voice-123")
            );
            Map<String, Object> cost = (Map<String, Object>) generated.metadata().get("costMetadata");
            Map<String, Object> usage = (Map<String, Object>) cost.get("usage");

            assertEquals(41, usage.get("providerReportedCharacters"));
            assertEquals("ELEVENLABS_CHARACTER_COST_RESPONSE_HEADER", usage.get("providerUsageSource"));
            assertEquals("eleven-request-41", usage.get("providerRequestId"));
            assertEquals("CHARACTER_CREDIT", cost.get("rateUnit"));
            assertEquals(new BigDecimal("0.00123000"), cost.get("actualTotalCost"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsStoredFounderVideoToFileEndpointForFirstMinimaxClone() throws Exception {
        AtomicReference<String> capturedContentType = new AtomicReference<>("");
        AtomicReference<byte[]> capturedBody = new AtomicReference<>(new byte[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creator/avatar/voice/files", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = """
                    {
                      "audioContent": "YXVkaW8=",
                      "contentType": "audio/wav",
                      "provider": "fal.ai",
                      "model": "fal-ai/minimax/voice-clone",
                      "providerVoiceId": "minimax-clone-1"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        System.setProperty("dalai.llama.ai-service-url", "http://127.0.0.1:" + server.getAddress().getPort());
        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        when(storage.openObjectStream("creator-assets", "founders/source.mov")).thenReturn(
                new AssetStorageService.StreamedObject(
                        new ByteArrayInputStream("uploaded-video".getBytes(StandardCharsets.UTF_8)),
                        "video/quicktime",
                        14
                )
        );

        CreatorProperties properties = new CreatorProperties();
        properties.getAi().setTtsProvider("dalai_llama");
        properties.getAi().setTimeoutMs(5000);
        GoogleChirpVoiceGenerationService service = new GoogleChirpVoiceGenerationService(
                properties,
                WebClient.builder(),
                new ObjectMapper(),
                storage
        );

        Map<String, Object> sourceAsset = new LinkedHashMap<>();
        sourceAsset.put("bucket", "creator-assets");
        sourceAsset.put("objectKey", "founders/source.mov");
        sourceAsset.put("contentType", "video/quicktime");
        sourceAsset.put("originalFilename", "founder.mov");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        profile.put("sourceAsset", sourceAsset);
        profile.put("localModels", Map.of("voiceModel", "fal_minimax_voice_clone"));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("requestId", "voice-test-123");
        options.put("provider", "dalai_llama");
        options.put("voiceModel", "fal_minimax_voice_clone");
        options.put("founderConsentConfirmed", true);
        options.put("founderAvatarProfile", profile);

        try {
            GoogleChirpVoiceGenerationService.GeneratedVoice generated = service.generateVoice("Hello founder", options);

            verify(storage).openObjectStream(eq("creator-assets"), eq("founders/source.mov"));
            assertTrue(capturedContentType.get().startsWith("multipart/form-data"));
            String multipartBody = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(multipartBody.contains("uploaded-video"));
            assertTrue(multipartBody.contains("voice-test-123"));
            assertArrayEquals("audio".getBytes(StandardCharsets.UTF_8), generated.bytes());
            assertEquals("minimax-clone-1", generated.metadata().get("providerVoiceId"));
            assertEquals("voice-test-123", generated.providerRequest().get("requestId"));
        } finally {
            System.clearProperty("dalai.llama.ai-service-url");
            server.stop(0);
        }
    }

    @Test
    void generatesEnglishSourceSpeechBeforeCallingClientRvcAdapter() throws Exception {
        AtomicReference<byte[]> capturedVoiceBody = new AtomicReference<>(new byte[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/text:synthesize", exchange -> {
            byte[] response = """
                    {
                      "audioContent": "c291cmNlLWF1ZGlv"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/creator/avatar/voice/files", exchange -> {
            capturedVoiceBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = """
                    {
                      "audioContent": "Y2xvbmVk",
                      "contentType": "audio/wav",
                      "provider": "dalai_llama",
                      "model": "client_rvc_english",
                      "voiceProfileId": "founder_female_v1",
                      "providerVoiceId": "rvc:founder_female_v1",
                      "costMetadata": {
                        "provider": "dalai_llama",
                        "model": "client_rvc_english",
                        "actualTotalCost": 0
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setProperty("dalai.llama.ai-service-url", baseUrl);

        CreatorProperties properties = new CreatorProperties();
        properties.getAi().setTtsProvider("dalai_llama");
        properties.getAi().setGoogleTtsBaseUrl(baseUrl);
        properties.getAi().setGoogleTtsApiKey("test-key");
        properties.getAi().setTimeoutMs(5000);
        GoogleChirpVoiceGenerationService service = new GoogleChirpVoiceGenerationService(
                properties,
                WebClient.builder(),
                new ObjectMapper(),
                mock(AssetStorageService.class)
        );

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("consentConfirmed", true);
        profile.put("voiceProfileId", "founder_female_v1");
        profile.put("localModels", Map.of(
                "voiceModel", "client_rvc_english",
                "voiceProfileId", "founder_female_v1"
        ));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("requestId", "voice-rvc-test-123");
        options.put("provider", "dalai_llama");
        options.put("voiceModel", "client_rvc_english");
        options.put("voiceProfileId", "founder_female_v1");
        options.put("founderConsentConfirmed", true);
        options.put("founderAvatarProfile", profile);
        options.put("language", "English");
        options.put("languageCode", "en-IN");

        try {
            GoogleChirpVoiceGenerationService.GeneratedVoice generated = service.generateVoice(
                    "English client dialogue",
                    options
            );

            String multipartBody = new String(capturedVoiceBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(multipartBody.contains("source-audio"));
            assertTrue(multipartBody.contains("desired_speech"));
            assertTrue(multipartBody.contains("founder_female_v1"));
            assertArrayEquals("cloned".getBytes(StandardCharsets.UTF_8), generated.bytes());
            assertEquals("client_rvc_english", generated.metadata().get("model"));
            assertEquals("rvc:founder_female_v1", generated.metadata().get("providerVoiceId"));
            assertEquals("founder_female_v1", generated.metadata().get("voiceProfileId"));
        } finally {
            System.clearProperty("dalai.llama.ai-service-url");
            server.stop(0);
        }
    }
}
