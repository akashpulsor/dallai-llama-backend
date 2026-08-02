package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GoogleChirpVoiceGenerationService {

    private static final Logger log = LoggerFactory.getLogger(GoogleChirpVoiceGenerationService.class);
    private static final String DEFAULT_GOOGLE_TTS_BASE_URL = "https://texttospeech.googleapis.com";
    private static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String DEFAULT_GEMINI_TTS_MODEL = "gemini-3.1-flash-tts-preview";
    private static final String DEFAULT_ELEVENLABS_BASE_URL = "https://api.elevenlabs.io";
    private static final String DEFAULT_DALAI_LLAMA_AI_SERVICE_URL = "http://ai-service.apps.svc.cluster.local:8601";
    private static final String DEFAULT_DALAI_LLAMA_VOICE_PATH = "/creator/avatar/voice";
    private static final String DEFAULT_DALAI_LLAMA_VOICE_FILE_PATH = "/creator/avatar/voice/files";
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final int AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES = 48 * 1024 * 1024;

    private final CreatorProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final AssetStorageService assetStorageService;

    public GoogleChirpVoiceGenerationService(
            CreatorProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AssetStorageService assetStorageService
    ) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.assetStorageService = assetStorageService;
    }

    public GeneratedVoice generateVoice(String text, Map<String, Object> options) {
        Map<String, Object> safeOptions = options == null ? Map.of() : options;
        String provider = normalizeProvider(firstText(
                value(safeOptions, "provider"),
                properties.getAi().getTtsProvider(),
                "google_chirp"
        ));
        GeneratedVoice generated;
        if ("elevenlabs".equals(provider)) {
            generated = generateElevenLabs(text, safeOptions);
        } else if ("dalai_llama".equals(provider)) {
            generated = generateDalaiLlamaVoice(text, safeOptions);
        } else {
            generated = generateGoogleChirp(text, safeOptions);
        }
        return withVoiceSelectionMetadata(generated, requestedVoiceGender(safeOptions));
    }

    private GeneratedVoice generateGoogleChirp(String text, Map<String, Object> options) {
        String safeText = requireText(text);
        String voiceName = selectedGoogleVoiceName(options);
        if (!googleCloudTtsReady()) {
            return generateGeminiTts(safeText, voiceName, options);
        }
        try {
            return generateGoogleCloudTts(safeText, voiceName, options);
        } catch (WebClientResponseException.Unauthorized ex) {
            if (!geminiTtsApiKey().isBlank()) {
                return generateGeminiTts(safeText, voiceName, options);
            }
            throw ex;
        }
    }

    private GeneratedVoice generateGoogleCloudTts(String safeText, String voiceName, Map<String, Object> options) {
        String languageCode = firstText(value(options, "languageCode"), properties.getAi().getGoogleTtsLanguageCode(), languageCodeFromVoice(voiceName));
        String encoding = firstText(value(options, "audioEncoding"), properties.getAi().getGoogleTtsAudioEncoding(), "MP3").toUpperCase(Locale.ROOT);
        BigDecimal speakingRate = decimal(value(options, "speakingRate"), BigDecimal.ONE);
        BigDecimal pitch = decimal(value(options, "pitch"), BigDecimal.ZERO);
        BigDecimal volumeGainDb = decimal(value(options, "volumeGainDb"), BigDecimal.ZERO);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", Map.of("text", safeText));
        request.put("voice", Map.of(
                "languageCode", languageCode,
                "name", voiceName
        ));
        request.put("audioConfig", Map.of(
                "audioEncoding", encoding,
                "speakingRate", speakingRate,
                "pitch", pitch,
                "volumeGainDb", volumeGainDb
        ));

        WebClient.RequestBodySpec post = googleClient().post().uri(uriBuilder -> {
            var builder = uriBuilder.path("/v1/text:synthesize");
            String apiKey = googleTtsApiKey();
            if (!apiKey.isBlank()) {
                builder.queryParam("key", apiKey);
            }
            return builder.build();
        });
        String apiKey = googleTtsApiKey();
        if (apiKey.isBlank()) {
            post.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken());
        }
        JsonNode response = post
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));

        String audioContent = textAt(response, "audioContent");
        if (audioContent.isBlank()) {
            throw new IllegalStateException("Google Chirp returned no audioContent.");
        }

        int characters = safeText.codePointCount(0, safeText.length());
        BigDecimal rate = positiveDecimal(properties.getAi().getGoogleTtsUsdPerMillionChars(), BigDecimal.valueOf(30));
        BigDecimal actualCost = BigDecimal.valueOf(characters)
                .multiply(rate)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
        Map<String, Object> metadata = voiceMetadata(
                "google_chirp",
                voiceName,
                languageCode,
                encoding,
                characters,
                rate,
                actualCost,
                "USD_PER_MILLION_CHARS"
        );
        metadata.put("baseUrl", firstText(properties.getAi().getGoogleTtsBaseUrl(), DEFAULT_GOOGLE_TTS_BASE_URL));
        metadata.put("providerResponsePath", "audioContent");

        return new GeneratedVoice(
                decodeBase64(audioContent),
                contentTypeForEncoding(encoding),
                metadata,
                request,
                response == null ? Map.of() : objectMapper.convertValue(response, new TypeReference<>() {})
        );
    }

    private GeneratedVoice generateGeminiTts(String safeText, String voiceName, Map<String, Object> options) {
        String apiKey = geminiTtsApiKey();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Gemini TTS API key is not configured. Set GEMINI_API_KEY or GOOGLE_API_KEY.");
        }
        String model = firstText(value(options, "model"), System.getenv("GEMINI_TTS_MODEL"), DEFAULT_GEMINI_TTS_MODEL);
        String geminiVoice = geminiVoiceName(firstText(value(options, "geminiVoice"), value(options, "voice"), voiceName));
        String languageCode = firstText(value(options, "languageCode"), properties.getAi().getGoogleTtsLanguageCode(), languageCodeFromVoice(voiceName));
        String prompt = firstText(value(options, "stylePrompt"), value(options, "voiceInstruction"), "Read clearly with natural ad voiceover energy:") + "\n" + safeText;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("input", prompt);
        request.put("response_format", Map.of("type", "audio"));
        request.put("generation_config", Map.of(
                "speech_config", List.of(Map.of("voice", geminiVoice))
        ));

        JsonNode response = geminiClient()
                .post()
                .uri("/interactions")
                .header("x-goog-api-key", apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));

        String audioContent = firstText(
                textAt(response, "output_audio", "data"),
                textAt(response, "outputAudio", "data"),
                textAt(response, "output", "audio", "data"),
                textAt(response, "audio", "data"),
                textAt(response, "inline_data", "data"),
                textAt(response, "inlineData", "data"),
                textAt(response, "candidates", "0", "content", "parts", "0", "inlineData", "data"),
                textAt(response, "candidates", "0", "content", "parts", "0", "inline_data", "data"),
                recursiveAudioBase64(response)
        );
        if (audioContent.isBlank()) {
            throw new IllegalStateException("Gemini TTS returned no output audio. response=" + truncate(response == null ? "" : response.toString(), 1200));
        }

        byte[] pcmBytes = decodeBase64(audioContent);
        byte[] wavBytes = wavFromLinear16Pcm(pcmBytes, 24000, 1);
        int characters = safeText.codePointCount(0, safeText.length());
        BigDecimal rate = positiveDecimal(decimal(System.getenv("GEMINI_TTS_USD_PER_MILLION_CHARS"), properties.getAi().getGoogleTtsUsdPerMillionChars()), BigDecimal.valueOf(30));
        BigDecimal actualCost = BigDecimal.valueOf(characters)
                .multiply(rate)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
        Map<String, Object> metadata = voiceMetadata(
                "gemini_tts",
                model,
                languageCode,
                "LINEAR16_WAV",
                characters,
                rate,
                actualCost,
                "USD_PER_MILLION_CHARS"
        );
        metadata.put("voiceName", geminiVoice);
        metadata.put("requestedGoogleVoiceName", voiceName);
        metadata.put("baseUrl", firstText(System.getenv("GEMINI_BASE_URL"), DEFAULT_GEMINI_BASE_URL));
        metadata.put("providerResponsePath", "output_audio.data");

        return new GeneratedVoice(
                wavBytes,
                "audio/wav",
                metadata,
                request,
                response == null ? Map.of() : objectMapper.convertValue(response, new TypeReference<>() {})
        );
    }

    private GeneratedVoice generateElevenLabs(String text, Map<String, Object> options) {
        String safeText = requireText(text);
        String apiKey = firstText(properties.getAi().getElevenLabsApiKey(), System.getenv("ELEVENLABS_API_KEY"));
        if (apiKey.isBlank()) {
            throw new IllegalStateException("ElevenLabs API key is not configured. Set ELEVENLABS_API_KEY.");
        }
        String voiceId = firstText(value(options, "voiceId"), value(options, "voice"), properties.getAi().getElevenLabsVoiceId(), "21m00Tcm4TlvDq8ikWAM");
        String model = firstText(value(options, "model"), properties.getAi().getElevenLabsModel(), "eleven_multilingual_v2");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("text", safeText);
        request.put("model_id", model);
        request.put("voice_settings", Map.of(
                "stability", decimal(value(options, "stability"), BigDecimal.valueOf(0.45)),
                "similarity_boost", decimal(value(options, "similarityBoost"), BigDecimal.valueOf(0.75))
        ));

        ResponseEntity<byte[]> response = elevenLabsClient(apiKey)
                .post()
                .uri("/v1/text-to-speech/{voiceId}", voiceId)
                .bodyValue(request)
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));
        byte[] bytes = response == null || response.getBody() == null ? new byte[0] : response.getBody();
        if (bytes.length == 0) {
            throw new IllegalStateException("ElevenLabs returned empty audio.");
        }

        int characters = safeText.codePointCount(0, safeText.length());
        int providerReportedCharacters = positiveInt(
                response == null ? null : response.getHeaders().getFirst("character-cost"),
                characters
        );
        BigDecimal rate = positiveDecimal(properties.getAi().getElevenLabsUsdPerMillionChars(), BigDecimal.valueOf(30));
        BigDecimal actualCost = BigDecimal.valueOf(providerReportedCharacters)
                .multiply(rate)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
        Map<String, Object> metadata = voiceMetadata(
                "elevenlabs",
                voiceId,
                firstText(value(options, "languageCode"), "auto"),
                "MP3",
                characters,
                rate,
                actualCost,
                "CHARACTER_CREDIT"
        );
        metadata.put("model", model);
        metadata.put("voiceId", voiceId);
        Map<String, Object> costMetadata = new LinkedHashMap<>(mapValue(metadata.get("costMetadata")));
        Map<String, Object> usage = new LinkedHashMap<>(mapValue(costMetadata.get("usage")));
        usage.put("characters", characters);
        usage.put("billableCharacters", characters);
        usage.put("providerReportedCharacters", providerReportedCharacters);
        usage.put("providerUsageSource", response != null && response.getHeaders().containsKey("character-cost")
                ? "ELEVENLABS_CHARACTER_COST_RESPONSE_HEADER"
                : "COMPLETED_TEXT_CHARACTER_FALLBACK");
        String providerRequestId = response == null ? "" : firstText(response.getHeaders().getFirst("request-id"));
        String providerTraceId = response == null ? "" : firstText(response.getHeaders().getFirst("x-trace-id"));
        if (!providerRequestId.isBlank()) usage.put("providerRequestId", providerRequestId);
        if (!providerTraceId.isBlank()) usage.put("providerTraceId", providerTraceId);
        costMetadata.put("usage", usage);
        costMetadata.put("providerReportedCharacters", providerReportedCharacters);
        costMetadata.put("pricingSource", usage.get("providerUsageSource"));
        metadata.put("costMetadata", costMetadata);

        Map<String, Object> providerResponse = new LinkedHashMap<>();
        providerResponse.put("statusCode", response == null ? 0 : response.getStatusCode().value());
        providerResponse.put("characterCost", providerReportedCharacters);
        if (!providerRequestId.isBlank()) providerResponse.put("requestId", providerRequestId);
        if (!providerTraceId.isBlank()) providerResponse.put("traceId", providerTraceId);
        return new GeneratedVoice(bytes, "audio/mpeg", metadata, request, providerResponse);
    }

    private GeneratedVoice generateDalaiLlamaVoice(String text, Map<String, Object> options) {
        String safeText = requireText(text);
        String requestId = firstText(value(options, "requestId"), "voice-" + java.util.UUID.randomUUID());
        Map<String, Object> founderProfile = mapValue(value(options, "founderAvatarProfile"));
        Map<String, Object> sourceAsset = mapValue(founderProfile.get("sourceAsset"));
        Map<String, Object> localModels = mapValue(firstValue(
                value(options, "localModels"),
                value(options, "localAvatarModels"),
                founderProfile.get("localModels")
        ));
        String model = normalizeLocalVoiceModel(firstText(
                value(options, "voiceModel"),
                value(options, "model"),
                localModels.get("voiceModel"),
                System.getenv("AVATAR_VOICE_MODEL"),
                "fal_minimax_voice_clone"
        ));
        boolean clientRvcVoice = "client_rvc_english".equals(model);
        String languageCode = clientRvcVoice
                ? "en-IN"
                : firstText(value(options, "languageCode"), value(options, "dialogueLanguageCode"), "hi-IN");
        String language = clientRvcVoice
                ? "English"
                : firstText(value(options, "language"), value(options, "dialogueLanguage"), "Hinglish");
        String voiceProfileId = clientRvcVoice
                ? firstText(
                        value(options, "voiceProfileId"),
                        localModels.get("voiceProfileId"),
                        founderProfile.get("voiceProfileId"),
                        "founder_female_v1"
                )
                : firstText(value(options, "voiceProfileId"), founderProfile.get("voiceProfileId"));
        String voiceId = firstText(value(options, "voiceId"), value(options, "founderVoiceId"));
        String voiceEmbeddingId = firstText(value(options, "voiceEmbeddingId"), value(options, "founderVoiceEmbeddingId"));
        String minimaxVoiceId = "fal_minimax_voice_clone".equals(model)
                ? firstText(
                        value(options, "minimaxVoiceId"),
                        value(options, "providerVoiceId"),
                        founderProfile.get("minimaxVoiceId"),
                        founderProfile.get("providerVoiceId")
                )
                : "";
        boolean elevenLabsVoiceModel = "fal_elevenlabs_v3".equals(model)
                || "elevenlabs_v3_voice_clone".equals(model)
                || "elevenlabs_professional".equals(model);
        String elevenLabsVoiceId = elevenLabsVoiceModel
                ? firstText(
                        value(options, "elevenLabsVoiceId"),
                        value(options, "proprietaryVoiceId"),
                        value(options, "providerVoiceId"),
                        founderProfile.get("elevenLabsVoiceId"),
                        founderProfile.get("proprietaryVoiceId"),
                        founderProfile.get("providerVoiceId")
                )
                : "";
        String sarvamVoiceId = "sarvam_voice_clone".equals(model)
                ? firstText(
                        value(options, "sarvamVoiceId"),
                        value(options, "providerVoiceId"),
                        founderProfile.get("sarvamVoiceId"),
                        founderProfile.get("providerVoiceId")
                )
                : "";
        String selectedProviderVoiceId = firstText(minimaxVoiceId, elevenLabsVoiceId, sarvamVoiceId);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", requestId);
        request.put("text", safeText);
        request.put("spokenText", firstText(value(options, "spokenText"), safeText));
        request.put("captionText", firstText(value(options, "captionText"), safeText));
        request.put("pronunciationGuide", firstText(value(options, "pronunciationGuide"), founderProfile.get("pronunciationGuide")));
        request.put("promptText", firstText(value(options, "promptText"), value(options, "referenceTranscript"), founderProfile.get("referenceTranscript")));
        request.put("referenceTranscript", firstText(value(options, "referenceTranscript"), value(options, "promptText"), founderProfile.get("referenceTranscript")));
        request.put("provider", "dalai_llama");
        request.put("model", model);
        request.put("voiceModel", model);
        request.put("voiceId", voiceId);
        request.put("minimaxVoiceId", minimaxVoiceId);
        request.put("providerVoiceId", selectedProviderVoiceId);
        request.put("voiceEmbeddingId", voiceEmbeddingId);
        request.put("voiceProfileId", voiceProfileId);
        request.put("elevenLabsVoiceId", elevenLabsVoiceId);
        request.put("sarvamVoiceId", sarvamVoiceId);
        request.put("avatarId", firstText(value(options, "avatarId"), value(options, "founderAvatarId")));
        request.put("language", language);
        request.put("languageCode", languageCode);
        request.put("referenceLanguage", firstText(value(options, "referenceLanguage"), founderProfile.get("referenceLanguage"), "English"));
        request.put("languageBoost", firstText(value(options, "languageBoost"), founderProfile.get("minimaxLanguageBoost"), "auto"));
        request.put("gpuProfile", firstText(value(options, "gpuProfile"), System.getenv("AVATAR_GENERATION_GPU_PROFILE"), "rtx_4060_8gb"));
        request.put("stylePrompt", firstText(value(options, "stylePrompt"), value(options, "voiceInstruction"), "Natural Hinglish founder voiceover with clear pronunciation and confident ad energy."));
        request.put("sourcePurpose", clientRvcVoice ? "desired_speech" : "founder_reference");
        request.put("preview", booleanValue(value(options, "preview"), false));
        request.put("consentConfirmed", booleanValue(firstValue(
                value(options, "founderConsentConfirmed"),
                value(options, "consentConfirmed"),
                founderProfile.get("consentConfirmed")
        ), false));
        if (!localModels.isEmpty()) {
            request.put("localModels", localModels);
        }
        if (!founderProfile.isEmpty()) {
            request.put("founderAvatarProfile", founderProfile);
            request.put("founderKit", founderProfile);
        }
        String sourceUrl = firstText(
                value(options, "sourceUrl"),
                founderProfile.get("sourceUrl"),
                founderProfile.get("signedUrl"),
                founderProfile.get("publicUrl"),
                sourceAsset.get("signedUrl"),
                sourceAsset.get("publicUrl"),
                sourceAsset.get("assetUrl")
        );
        if (!sourceUrl.isBlank()) {
            request.put("sourceUrl", sourceUrl);
        }

        String sourceObjectKey = firstText(sourceAsset.get("objectKey"), founderProfile.get("sourceObjectKey"));
        boolean useMultipartReference = ("fal_minimax_voice_clone".equals(model) && minimaxVoiceId.isBlank()
                || "fal_chatterbox_multilingual".equals(model)
                || "elevenlabs_v3_voice_clone".equals(model) && elevenLabsVoiceId.isBlank()
                || "sarvam_voice_clone".equals(model) && sarvamVoiceId.isBlank())
                && !sourceObjectKey.isBlank();
        GeneratedVoice rvcSourceVoice = clientRvcVoice
                ? generateClientRvcSourceSpeech(firstText(request.get("spokenText"), safeText), options)
                : null;
        long requestStartedNanos = System.nanoTime();
        log.info(
                "Founder voice AI request started requestId={} model={} transport={} preview={} textChars={} hasExistingProviderVoice={} hasStoredReference={}",
                requestId,
                model,
                clientRvcVoice ? "source_speech_file" : useMultipartReference ? "multipart_file" : "json",
                booleanValue(request.get("preview"), false),
                safeText.codePointCount(0, safeText.length()),
                !selectedProviderVoiceId.isBlank(),
                !sourceObjectKey.isBlank()
        );
        JsonNode response;
        try {
            response = clientRvcVoice
                    ? generateDalaiLlamaVoiceFromSourceSpeech(request, rvcSourceVoice)
                    : useMultipartReference
                            ? generateDalaiLlamaVoiceFromStoredReference(request, sourceAsset, founderProfile)
                            : generateDalaiLlamaVoiceFromJson(request);
        } catch (RuntimeException ex) {
            log.error(
                    "Founder voice AI request failed requestId={} model={} transport={} elapsedMs={} errorType={} errorMessage={}",
                    requestId,
                    model,
                    clientRvcVoice ? "source_speech_file" : useMultipartReference ? "multipart_file" : "json",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedNanos),
                    ex.getClass().getSimpleName(),
                    firstText(ex.getMessage(), "unknown")
            );
            throw ex;
        }

        String audioContent = firstText(
                textAt(response, "audioContent"),
                textAt(response, "audioBase64"),
                textAt(response, "audio", "base64"),
                textAt(response, "audio", "data"),
                textAt(response, "data", "audioBase64"),
                textAt(response, "data", "audioContent"),
                textAt(response, "result", "audioBase64"),
                textAt(response, "result", "audioContent"),
                recursiveAudioBase64(response)
        );
        Map<String, Object> responseMap = response == null
                ? Map.of()
                : objectMapper.convertValue(response, new TypeReference<>() {});
        if (audioContent.isBlank()) {
            String reason = firstText(
                    textValue(responseMap, "manualApprovalReason"),
                    textValue(responseMap, "message"),
                    "Dalai Llama voice generation returned no inline audio."
            );
            throw new IllegalStateException(reason);
        }
        log.info(
                "Founder voice AI response received requestId={} model={} elapsedMs={} hasAudio={} audioBase64Chars={} hasProviderVoice={}",
                requestId,
                model,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedNanos),
                true,
                audioContent.length(),
                !firstText(
                        responseMap.get("providerVoiceId"),
                        responseMap.get("customVoiceId"),
                        responseMap.get("custom_voice_id"),
                        selectedProviderVoiceId
                ).isBlank()
        );

        int characters = safeText.codePointCount(0, safeText.length());
        Map<String, Object> providerCostMetadata = new LinkedHashMap<>(mapValue(responseMap.get("costMetadata")));
        for (String key : List.of("falRequestId", "avatarRequestId", "lipSyncRequestId", "requestId")) {
            String providerRequestId = textValue(responseMap, key);
            if (!providerRequestId.isBlank()) {
                providerCostMetadata.putIfAbsent(key, providerRequestId);
            }
        }
        String actualProvider = firstText(providerCostMetadata.get("provider"), responseMap.get("provider"), "dalai_llama");
        String actualModel = firstText(providerCostMetadata.get("model"), responseMap.get("voiceModel"), responseMap.get("model"), model);
        BigDecimal adapterCost = decimal(firstValue(
                providerCostMetadata.get("actualTotalCost"),
                providerCostMetadata.get("totalCost"),
                responseMap.get("cost")
        ), BigDecimal.ZERO).max(BigDecimal.ZERO);
        Map<String, Object> sourceTtsCostMetadata = rvcSourceVoice == null
                ? Map.of()
                : mapValue(rvcSourceVoice.metadata().get("costMetadata"));
        BigDecimal sourceTtsCost = decimal(firstValue(
                sourceTtsCostMetadata.get("actualTotalCost"),
                sourceTtsCostMetadata.get("totalCost")
        ), BigDecimal.ZERO).max(BigDecimal.ZERO);
        BigDecimal actualCost = adapterCost.add(sourceTtsCost);
        if (clientRvcVoice) {
            providerCostMetadata.put("provider", "dalai_llama");
            providerCostMetadata.put("model", model);
            providerCostMetadata.put("currency", "USD");
            providerCostMetadata.put("actualTotalCost", actualCost);
            providerCostMetadata.put("totalCost", actualCost);
            providerCostMetadata.put("sourceTtsCost", sourceTtsCost);
            providerCostMetadata.put("adapterCost", adapterCost);
        }
        Map<String, Object> metadata = voiceMetadata(
                actualProvider,
                actualModel,
                languageCode,
                firstText(textAt(response, "audioEncoding"), textAt(response, "contentType"), "WAV"),
                characters,
                "fal.ai".equalsIgnoreCase(actualProvider) ? BigDecimal.valueOf(0.05) : BigDecimal.ZERO,
                actualCost,
                "fal.ai".equalsIgnoreCase(actualProvider) ? "USD_PER_1000_CHARACTERS" : "LOCAL_OR_MANUAL_APPROVAL"
        );
        if (!providerCostMetadata.isEmpty()) {
            metadata.put("costMetadata", providerCostMetadata);
        }
        metadata.put("voiceId", voiceId);
        metadata.put("voiceEmbeddingId", voiceEmbeddingId);
        metadata.put("voiceProfileId", voiceProfileId);
        if (rvcSourceVoice != null) {
            metadata.put("sourceTts", rvcSourceVoice.metadata());
        }
        String providerVoiceId = firstText(
                responseMap.get("providerVoiceId"),
                responseMap.get("customVoiceId"),
                responseMap.get("custom_voice_id"),
                selectedProviderVoiceId
        );
        if (!providerVoiceId.isBlank()) {
            metadata.put("providerVoiceId", providerVoiceId);
            metadata.put("customVoiceId", providerVoiceId);
            if (elevenLabsVoiceModel) {
                metadata.put("elevenLabsVoiceId", providerVoiceId);
            }
            if ("sarvam_voice_clone".equals(model)) {
                metadata.put("sarvamVoiceId", providerVoiceId);
            }
        }
        metadata.put("language", language);
        metadata.put("baseUrl", dalaiLlamaBaseUrl());
        metadata.put("providerResponsePath", "audioContent");

        return new GeneratedVoice(
                decodeBase64(audioContent),
                contentTypeForAudioResponse(response),
                metadata,
                request,
                responseMap
        );
    }

    private JsonNode generateDalaiLlamaVoiceFromJson(Map<String, Object> request) {
        String requestId = firstText(request.get("requestId"), "voice-unknown");
        log.info("Founder voice JSON handoff requestId={} path={}", requestId, DEFAULT_DALAI_LLAMA_VOICE_PATH);
        return dalaiLlamaClient()
                .post()
                .uri(firstText(
                        System.getenv("DALAI_LLAMA_VOICE_PATH"),
                        System.getenv("DALLAI_LLAMA_VOICE_PATH"),
                        DEFAULT_DALAI_LLAMA_VOICE_PATH
                ))
                .headers(this::applyDalaiLlamaAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));
    }

    private GeneratedVoice generateClientRvcSourceSpeech(String text, Map<String, Object> options) {
        Map<String, Object> sourceOptions = new LinkedHashMap<>(options == null ? Map.of() : options);
        sourceOptions.remove("provider");
        sourceOptions.remove("model");
        sourceOptions.remove("voiceModel");
        sourceOptions.remove("voiceProfileId");
        sourceOptions.remove("voice");
        sourceOptions.remove("voiceName");
        sourceOptions.remove("geminiVoice");
        sourceOptions.put("language", "English");
        sourceOptions.put("languageCode", "en-IN");
        sourceOptions.put("voiceGender", "female");
        sourceOptions.put(
                "voiceName",
                firstText(System.getenv("CLIENT_RVC_SOURCE_VOICE_NAME"), "en-IN-Chirp3-HD-Aoede")
        );
        sourceOptions.put(
                "stylePrompt",
                firstText(
                        value(options, "sourceTtsStylePrompt"),
                        "Speak in clear, natural Indian English. Preserve punctuation, emphasis, and pauses:"
                )
        );
        return generateGoogleChirp(text, sourceOptions);
    }

    private JsonNode generateDalaiLlamaVoiceFromSourceSpeech(
            Map<String, Object> request,
            GeneratedVoice sourceVoice
    ) {
        if (sourceVoice == null || sourceVoice.bytes() == null || sourceVoice.bytes().length == 0) {
            throw new IllegalStateException("English source TTS returned no audio for the client voice adapter.");
        }
        String requestId = firstText(request.get("requestId"), "voice-unknown");
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        ByteArrayResource sourceSpeech = new ByteArrayResource(sourceVoice.bytes()) {
            @Override
            public String getFilename() {
                return "english-source-speech" + fileSuffixForMedia(sourceVoice.contentType());
            }
        };
        multipart.part("sample", sourceSpeech)
                .filename(sourceSpeech.getFilename())
                .contentType(mediaType(sourceVoice.contentType(), MediaType.APPLICATION_OCTET_STREAM));
        addMultipartText(multipart, "text", request.get("text"));
        addMultipartText(multipart, "requestId", requestId);
        addMultipartText(multipart, "provider", request.get("provider"));
        addMultipartText(multipart, "model", request.get("model"));
        addMultipartText(multipart, "voiceModel", request.get("voiceModel"));
        addMultipartText(multipart, "voiceProfileId", request.get("voiceProfileId"));
        addMultipartText(multipart, "voiceId", request.get("voiceId"));
        addMultipartText(multipart, "voiceEmbeddingId", request.get("voiceEmbeddingId"));
        addMultipartText(multipart, "avatarId", request.get("avatarId"));
        addMultipartText(multipart, "language", request.get("language"));
        addMultipartText(multipart, "languageCode", request.get("languageCode"));
        addMultipartText(multipart, "referenceLanguage", request.get("referenceLanguage"));
        addMultipartText(multipart, "stylePrompt", request.get("stylePrompt"));
        addMultipartText(multipart, "spokenText", request.get("spokenText"));
        addMultipartText(multipart, "captionText", request.get("captionText"));
        addMultipartText(multipart, "pronunciationGuide", request.get("pronunciationGuide"));
        addMultipartText(multipart, "gpuProfile", request.get("gpuProfile"));
        addMultipartText(multipart, "consentConfirmed", request.get("consentConfirmed"));
        addMultipartText(multipart, "sourcePurpose", request.get("sourcePurpose"));
        addMultipartText(multipart, "founderAvatarProfileJson", jsonValue(request.get("founderAvatarProfile")));
        addMultipartText(multipart, "localModelsJson", jsonValue(request.get("localModels")));
        addMultipartText(multipart, "manualApprovalRequiredForFallback", request.get("manualApprovalRequiredForFallback"));
        addMultipartText(multipart, "fallbackProvider", request.get("fallbackProvider"));
        addMultipartText(multipart, "preview", request.get("preview"));

        log.info(
                "Client RVC source-speech handoff started requestId={} bytes={} profileId={}",
                requestId,
                sourceVoice.bytes().length,
                firstText(request.get("voiceProfileId"))
        );
        return dalaiLlamaClient()
                .post()
                .uri(firstText(
                        System.getenv("DALAI_LLAMA_VOICE_FILE_PATH"),
                        System.getenv("DALLAI_LLAMA_VOICE_FILE_PATH"),
                        DEFAULT_DALAI_LLAMA_VOICE_FILE_PATH
                ))
                .headers(this::applyDalaiLlamaAuth)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipart.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));
    }

    private JsonNode generateDalaiLlamaVoiceFromStoredReference(
            Map<String, Object> request,
            Map<String, Object> sourceAsset,
            Map<String, Object> founderProfile
    ) {
        String requestId = firstText(request.get("requestId"), "voice-unknown");
        String objectKey = firstText(sourceAsset.get("objectKey"), founderProfile.get("sourceObjectKey"));
        String bucket = firstText(sourceAsset.get("bucket"), assetStorageService.creatorAssetsBucket());
        String contentType = firstText(
                sourceAsset.get("contentType"),
                founderProfile.get("sourceContentType"),
                "video/mp4"
        );
        String originalFilename = firstText(
                sourceAsset.get("originalFilename"),
                "founder-reference" + fileSuffixForMedia(contentType)
        );
        try (AssetStorageService.StreamedObject storedObject = assetStorageService.openObjectStream(bucket, objectKey)) {
            long referenceBytes = storedObject.sizeBytes();
            String storedContentType = firstText(storedObject.contentType(), contentType);
            log.info(
                    "Founder voice reference stream opened requestId={} bytes={} contentType={} bucketConfigured={} objectKeyPresent={}",
                    requestId,
                    referenceBytes,
                    storedContentType,
                    !bucket.isBlank(),
                    !objectKey.isBlank()
            );

            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            InputStreamResource mediaStream = new InputStreamResource(storedObject.inputStream()) {
                @Override
                public String getFilename() {
                    return originalFilename;
                }

                @Override
                public long contentLength() {
                    return referenceBytes;
                }
            };
            multipart.part("sample", mediaStream)
                    .filename(originalFilename)
                    .contentType(mediaType(storedContentType, MediaType.APPLICATION_OCTET_STREAM));
            addMultipartText(multipart, "text", request.get("text"));
            addMultipartText(multipart, "requestId", requestId);
            addMultipartText(multipart, "provider", request.get("provider"));
            addMultipartText(multipart, "model", request.get("model"));
            addMultipartText(multipart, "voiceModel", request.get("voiceModel"));
            addMultipartText(multipart, "voiceProfileId", request.get("voiceProfileId"));
            addMultipartText(multipart, "voiceId", request.get("voiceId"));
            addMultipartText(multipart, "providerVoiceId", request.get("providerVoiceId"));
            addMultipartText(multipart, "minimaxVoiceId", request.get("minimaxVoiceId"));
            addMultipartText(multipart, "elevenLabsVoiceId", request.get("elevenLabsVoiceId"));
            addMultipartText(multipart, "sarvamVoiceId", request.get("sarvamVoiceId"));
            addMultipartText(multipart, "voiceEmbeddingId", request.get("voiceEmbeddingId"));
            addMultipartText(multipart, "avatarId", request.get("avatarId"));
            addMultipartText(multipart, "language", request.get("language"));
            addMultipartText(multipart, "languageCode", request.get("languageCode"));
            addMultipartText(multipart, "referenceLanguage", request.get("referenceLanguage"));
            addMultipartText(multipart, "languageBoost", request.get("languageBoost"));
            addMultipartText(multipart, "stylePrompt", request.get("stylePrompt"));
            addMultipartText(multipart, "spokenText", request.get("spokenText"));
            addMultipartText(multipart, "captionText", request.get("captionText"));
            addMultipartText(multipart, "pronunciationGuide", request.get("pronunciationGuide"));
            addMultipartText(multipart, "promptText", request.get("promptText"));
            addMultipartText(multipart, "referenceTranscript", request.get("referenceTranscript"));
            addMultipartText(multipart, "gpuProfile", request.get("gpuProfile"));
            addMultipartText(multipart, "consentConfirmed", request.get("consentConfirmed"));
            addMultipartText(multipart, "sourcePurpose", request.get("sourcePurpose"));
            addMultipartText(multipart, "founderAvatarProfileJson", jsonValue(founderProfile));
            addMultipartText(multipart, "localModelsJson", jsonValue(request.get("localModels")));
            addMultipartText(multipart, "manualApprovalRequiredForFallback", request.get("manualApprovalRequiredForFallback"));
            addMultipartText(multipart, "fallbackProvider", request.get("fallbackProvider"));
            addMultipartText(multipart, "preview", request.get("preview"));

            log.info(
                    "Founder voice MinIO stream handoff started requestId={} path={} bytes={} model={}",
                    requestId,
                    DEFAULT_DALAI_LLAMA_VOICE_FILE_PATH,
                    referenceBytes,
                    firstText(request.get("voiceModel"), request.get("model"))
            );
            JsonNode response = dalaiLlamaClient()
                    .post()
                    .uri(firstText(
                            System.getenv("DALAI_LLAMA_VOICE_FILE_PATH"),
                            System.getenv("DALLAI_LLAMA_VOICE_FILE_PATH"),
                            DEFAULT_DALAI_LLAMA_VOICE_FILE_PATH
                    ))
                    .headers(this::applyDalaiLlamaAuth)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(multipart.build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(properties.getAi().getTimeoutMs(), 120000)));
            log.info(
                    "Founder voice MinIO stream handoff completed requestId={} responseStatus={} hasAudio={} hasProviderVoice={}",
                    requestId,
                    firstText(textAt(response, "status"), "unknown"),
                    !firstText(textAt(response, "audioContent"), textAt(response, "audioBase64")).isBlank(),
                    !firstText(textAt(response, "providerVoiceId"), textAt(response, "customVoiceId")).isBlank()
            );
            return response;
        } catch (IOException ex) {
            log.error(
                    "Founder voice MinIO stream failed requestId={} errorType={} errorMessage={}",
                    requestId,
                    ex.getClass().getSimpleName(),
                    firstText(ex.getMessage(), "unknown")
            );
            throw new IllegalStateException("Could not stream the uploaded founder reference to the voice clone service.", ex);
        }
    }

    private void applyDalaiLlamaAuth(HttpHeaders headers) {
        String apiKey = firstText(
                System.getenv("DALAI_LLAMA_AI_SERVICE_API_KEY"),
                System.getenv("DALAI_LLAMA_API_KEY"),
                System.getenv("DALLAI_LLAMA_API_KEY")
        );
        if (apiKey.isBlank()) {
            return;
        }
        String authHeader = firstText(System.getenv("DALAI_LLAMA_AUTH_HEADER"), "Authorization");
        String authPrefix = firstText(System.getenv("DALAI_LLAMA_AUTH_PREFIX"));
        headers.set(authHeader, authPrefix.isBlank() ? apiKey : authPrefix + " " + apiKey);
    }

    private void addMultipartText(MultipartBodyBuilder multipart, String name, Object value) {
        String text = firstText(value);
        if (!text.isBlank()) {
            multipart.part(name, text);
        }
    }

    private String jsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialize founder voice clone metadata.", ex);
        }
    }

    private MediaType mediaType(String value, MediaType fallback) {
        try {
            return MediaType.parseMediaType(firstText(value, fallback.toString()));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String fileSuffixForMedia(String contentType) {
        String normalized = firstText(contentType).toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) return ".mov";
        if (normalized.contains("webm")) return ".webm";
        if (normalized.contains("mpeg") && normalized.contains("audio")) return ".mp3";
        if (normalized.contains("wav")) return ".wav";
        if (normalized.startsWith("audio/")) return ".audio";
        return ".mp4";
    }

    private Map<String, Object> voiceMetadata(
            String provider,
            String model,
            String languageCode,
            String encoding,
            int characters,
            BigDecimal rate,
            BigDecimal actualCost,
            String rateUnit
    ) {
        Map<String, Object> costMetadata = new LinkedHashMap<>();
        costMetadata.put("modelApiInteracted", true);
        costMetadata.put("provider", provider);
        costMetadata.put("model", model);
        costMetadata.put("currency", "USD");
        costMetadata.put("rate", rate);
        costMetadata.put("rateUnit", rateUnit);
        costMetadata.put("characters", characters);
        costMetadata.put("billableCharacters", characters);
        costMetadata.put("actualTotalCost", actualCost);
        costMetadata.put("totalCost", actualCost);
        costMetadata.put("usage", Map.of("characters", characters));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider);
        metadata.put("model", model);
        metadata.put("voiceName", model);
        metadata.put("languageCode", languageCode);
        metadata.put("audioEncoding", encoding);
        metadata.put("characters", characters);
        metadata.put("costMetadata", costMetadata);
        metadata.put("audioMixStandards", Map.of(
                "dialogueLevel", "consistent_speech_first",
                "backgroundMusicDucking", "duck_under_speech",
                "fades", "smooth_fades_between_audio_segments"
        ));
        return metadata;
    }

    private WebClient googleClient() {
        return webClientBuilder
                .baseUrl(firstText(properties.getAi().getGoogleTtsBaseUrl(), DEFAULT_GOOGLE_TTS_BASE_URL))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private WebClient geminiClient() {
        return webClientBuilder
                .baseUrl(firstText(System.getenv("GEMINI_BASE_URL"), DEFAULT_GEMINI_BASE_URL))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private WebClient elevenLabsClient(String apiKey) {
        return webClientBuilder
                .baseUrl(firstText(properties.getAi().getElevenLabsBaseUrl(), DEFAULT_ELEVENLABS_BASE_URL))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader("xi-api-key", apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, "audio/mpeg")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private WebClient dalaiLlamaClient() {
        return webClientBuilder
                .baseUrl(dalaiLlamaBaseUrl())
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(AUDIO_RESPONSE_MAX_IN_MEMORY_BYTES))
                        .build())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String dalaiLlamaBaseUrl() {
        return firstText(
                System.getProperty("dalai.llama.ai-service-url"),
                System.getenv("DALAI_LLAMA_AI_SERVICE_URL"),
                System.getenv("DALLAI_LLAMA_AI_SERVICE_URL"),
                System.getenv("AI_SERVICE_URL"),
                DEFAULT_DALAI_LLAMA_AI_SERVICE_URL
        );
    }

    private String googleTtsApiKey() {
        return firstText(
                properties.getAi().getGoogleTtsApiKey(),
                System.getenv("GOOGLE_TTS_API_KEY"),
                System.getenv("GOOGLE_API_KEY"),
                System.getenv("GEMINI_API_KEY"),
                System.getenv("CREATOR_GEMINI_API_KEY")
        );
    }

    private boolean googleCloudTtsReady() {
        return !googleTtsApiKey().isBlank()
                || !firstText(System.getenv("GOOGLE_CREDENTIALS_JSON"), System.getenv("GOOGLE_APPLICATION_CREDENTIALS")).isBlank();
    }

    private String geminiTtsApiKey() {
        return firstText(
                System.getenv("GEMINI_API_KEY"),
                System.getenv("CREATOR_GEMINI_API_KEY"),
                System.getenv("GOOGLE_API_KEY")
        );
    }

    private String accessToken() {
        try {
            GoogleCredentials credentials = googleCredentials().createScoped(List.of(CLOUD_PLATFORM_SCOPE));
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                credentials.refresh();
                token = credentials.getAccessToken();
            }
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google application credentials did not return an access token.");
            }
            return token.getTokenValue();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load Google application credentials for Google Chirp.", ex);
        }
    }

    private GoogleCredentials googleCredentials() throws IOException {
        String credentialsJson = firstText(System.getenv("GOOGLE_CREDENTIALS_JSON"));
        if (!credentialsJson.isBlank()) {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        }
        return GoogleCredentials.getApplicationDefault();
    }

    private String normalizeProvider(String provider) {
        String normalized = firstText(provider, "google_chirp").toLowerCase(Locale.ROOT).replace("-", "_").trim();
        if (normalized.equals("google") || normalized.equals("chirp") || normalized.equals("google_tts") || normalized.equals("google_chirp3")) {
            return "google_chirp";
        }
        if (normalized.equals("eleven")
                || normalized.equals("eleven_labs")
                || normalized.equals("elevenlabs")
                || normalized.equals("11labs")) {
            return "elevenlabs";
        }
        if (normalized.equals("dalai_llama")
                || normalized.equals("dallai_llama")
                || normalized.equals("local")
                || normalized.equals("open_source")
                || normalized.equals("opensource")
                || normalized.equals("xtts")
                || normalized.equals("xtts_v2")
                || normalized.equals("cosy_voice")
                || normalized.equals("cosyvoice")
                || normalized.equals("cosy_voice2")
                || normalized.equals("cosyvoice2")
                || normalized.equals("fal")
                || normalized.equals("fal_ai")
                || normalized.equals("fal_f5")
                || normalized.equals("fal_f5_tts")
                || normalized.equals("fal_minimax_voice_clone")
                || normalized.equals("elevenlabs_v3_voice_clone")
                || normalized.equals("sarvam_voice_clone")
                || normalized.equals("client_rvc_english")
                || normalized.equals("trained_client_voice")
                || normalized.equals("founder_female_v1")
                || normalized.equals("minimax_voice_clone")
                || normalized.equals("f5")
                || normalized.equals("f5_tts")
                || normalized.equals("fs_tts")
                || normalized.equals("spark_tts")) {
            return "dalai_llama";
        }
        return "google_chirp";
    }

    private String normalizeLocalVoiceModel(String value) {
        String normalized = firstText(value, "fal_minimax_voice_clone").toLowerCase(Locale.ROOT).replace("-", "_").replaceAll("[^a-z0-9_]+", "_").trim();
        return switch (normalized) {
            case "client_rvc", "client_rvc_english", "trained_client_voice", "founder_female_v1" -> "client_rvc_english";
            case "fal", "fal_ai", "fal_f5", "fal_f5_tts", "f5", "f5_tts", "fal_minimax_voice_clone", "minimax_voice_clone", "minimax" -> "fal_minimax_voice_clone";
            case "cosy_voice", "cosyvoice", "cosyvoice_2", "cosy_voice_2", "cosyvoice2",
                 "fs", "fs_tts", "fstts", "spark", "spark_tts", "sparktts", "xtts", "xtts_v2" -> "fal_minimax_voice_clone";
            case "fal_elevenlabs", "fal_elevenlabs_v3" -> "fal_elevenlabs_v3";
            case "eleven_v3", "elevenlabs_v3", "elevenlabs_v3_voice_clone", "elevenlabs_direct",
                 "elevenlabs_ivc", "elevenlabs_instant_voice_clone" -> "elevenlabs_v3_voice_clone";
            case "sarvam", "sarvam_ai", "sarvam_voice", "sarvam_voice_clone", "sarvam_clone" -> "sarvam_voice_clone";
            case "fal_chatterbox", "fal_chatterbox_multilingual", "chatterbox", "chatterbox_multilingual" -> "fal_chatterbox_multilingual";
            case "eleven", "eleven_labs", "elevenlabs", "elevenlabs_voice_clone", "elevenlabs_professional", "elevenlabs_pvc", "11labs" -> "elevenlabs_professional";
            case "uploaded_audio", "uploaded_founder_audio", "founder_recording", "exact_voice" -> "uploaded_founder_audio";
            case "synthesia", "synthesia_managed", "synthesia_voice" -> "synthesia_managed";
            case "api", "api_fallback", "fallback", "proprietary", "proprietary_api" -> "api_fallback";
            default -> "fal_minimax_voice_clone";
        };
    }

    private String selectedGoogleVoiceName(Map<String, Object> options) {
        String explicitVoice = firstText(value(options, "voiceName"), value(options, "voice"));
        if (!explicitVoice.isBlank()) {
            return explicitVoice;
        }
        return switch (requestedVoiceGender(options)) {
            case "male" -> firstText(
                    properties.getAi().getGoogleTtsVoiceMale(),
                    properties.getAi().getGoogleTtsVoiceName(),
                    "en-US-Chirp3-HD-Charon"
            );
            case "female" -> firstText(
                    properties.getAi().getGoogleTtsVoiceFemale(),
                    properties.getAi().getGoogleTtsVoiceName(),
                    "en-US-Chirp3-HD-Aoede"
            );
            default -> firstText(properties.getAi().getGoogleTtsVoiceName(), "en-US-Chirp3-HD-Charon");
        };
    }

    private String requestedVoiceGender(Map<String, Object> options) {
        String normalized = firstText(
                value(options, "voiceGender"),
                value(options, "dialogueVoiceGender"),
                value(options, "speakerGender"),
                value(options, "gender")
        ).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        if (normalized.startsWith("female") || "woman".equals(normalized) || "girl".equals(normalized)) {
            return "female";
        }
        if (normalized.startsWith("male") || "man".equals(normalized) || "boy".equals(normalized)) {
            return "male";
        }
        return "";
    }

    private GeneratedVoice withVoiceSelectionMetadata(GeneratedVoice generated, String voiceGender) {
        Map<String, Object> metadata = new LinkedHashMap<>(generated.metadata() == null ? Map.of() : generated.metadata());
        metadata.put("requestedVoiceGender", voiceGender.isBlank() ? "unspecified" : voiceGender);
        metadata.put("voiceSelection", voiceGender.isBlank() ? "configured_default" : "cast_gender");
        return new GeneratedVoice(
                generated.bytes(),
                generated.contentType(),
                metadata,
                generated.providerRequest(),
                generated.providerResponse()
        );
    }

    private String languageCodeFromVoice(String voiceName) {
        String value = firstText(voiceName, "en-US");
        int chirp = value.indexOf("-Chirp");
        if (chirp > 0) {
            return value.substring(0, chirp);
        }
        return value.length() >= 5 ? value.substring(0, 5) : "en-US";
    }

    private String geminiVoiceName(String voiceName) {
        String value = firstText(voiceName, "Charon").trim();
        int marker = value.lastIndexOf("-Chirp3-HD-");
        if (marker >= 0) {
            value = value.substring(marker + "-Chirp3-HD-".length());
        }
        if (value.contains("-")) {
            value = value.substring(value.lastIndexOf('-') + 1);
        }
        return firstText(value, "Charon");
    }

    private String requireText(String text) {
        String safeText = firstText(text);
        if (safeText.isBlank()) {
            throw new IllegalArgumentException("Voice text is required.");
        }
        return safeText;
    }

    private String contentTypeForEncoding(String encoding) {
        return switch (firstText(encoding, "MP3").toUpperCase(Locale.ROOT)) {
            case "LINEAR16" -> "audio/wav";
            case "OGG_OPUS" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }

    private String contentTypeForAudioResponse(JsonNode response) {
        String value = firstText(
                textAt(response, "contentType"),
                textAt(response, "content_type"),
                textAt(response, "mimeType"),
                textAt(response, "mime_type"),
                textAt(response, "audio", "contentType"),
                textAt(response, "data", "contentType"),
                textAt(response, "result", "contentType")
        ).toLowerCase(Locale.ROOT);
        if (value.contains("ogg")) return "audio/ogg";
        if (value.contains("mpeg") || value.contains("mp3")) return "audio/mpeg";
        return "audio/wav";
    }

    private byte[] decodeBase64(String value) {
        String data = firstText(value);
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma >= 0) {
            data = data.substring(comma + 1);
        }
        return Base64.getDecoder().decode(data);
    }

    private byte[] wavFromLinear16Pcm(byte[] pcm, int sampleRate, int channels) {
        byte[] safePcm = pcm == null ? new byte[0] : pcm;
        int byteRate = sampleRate * channels * 2;
        int blockAlign = channels * 2;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + safePcm.length);
            writeAscii(out, "RIFF");
            writeIntLE(out, 36 + safePcm.length);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeIntLE(out, 16);
            writeShortLE(out, 1);
            writeShortLE(out, channels);
            writeIntLE(out, sampleRate);
            writeIntLE(out, byteRate);
            writeShortLE(out, blockAlign);
            writeShortLE(out, 16);
            writeAscii(out, "data");
            writeIntLE(out, safePcm.length);
            out.write(safePcm);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not wrap Gemini TTS PCM audio in WAV container.", ex);
        }
    }

    private void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String item : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return "";
            }
            current = item.matches("\\d+") ? current.path(Integer.parseInt(item)) : current.path(item);
        }
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private String recursiveAudioBase64(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            String mimeType = firstText(
                    textAt(node, "mimeType"),
                    textAt(node, "mime_type"),
                    textAt(node, "contentType"),
                    textAt(node, "content_type")
            ).toLowerCase(Locale.ROOT);
            String data = firstText(textAt(node, "data"), textAt(node, "bytesBase64Encoded"), textAt(node, "b64_json"));
            if (!data.isBlank() && mimeType.contains("audio")) {
                return data;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String value = recursiveAudioBase64(entry.getValue());
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = recursiveAudioBase64(item);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String truncate(String value, int maxChars) {
        String text = firstText(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "...";
    }

    private Object value(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }

    private Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? fallback : Boolean.parseBoolean(normalized);
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() {});
        }
        return new LinkedHashMap<>();
    }

    private String textValue(Map<String, Object> map, String key) {
        return firstText(value(map, key));
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal positiveDecimal(BigDecimal value, BigDecimal fallback) {
        BigDecimal resolved = value == null || value.signum() <= 0 ? fallback : value;
        return resolved == null ? BigDecimal.ZERO : resolved.max(BigDecimal.ZERO);
    }

    private int positiveInt(Object value, int fallback) {
        try {
            int parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(firstText(value).trim());
            return parsed > 0 ? parsed : Math.max(0, fallback);
        } catch (NumberFormatException ignored) {
            return Math.max(0, fallback);
        }
    }

    private String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    public record GeneratedVoice(
            byte[] bytes,
            String contentType,
            Map<String, Object> metadata,
            Map<String, Object> providerRequest,
            Map<String, Object> providerResponse
    ) {
    }
}
