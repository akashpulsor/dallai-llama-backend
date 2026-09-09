package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotBackgroundMusic;
import com.dalai.llama.preprod.dto.ShotBackgroundMusicView;
import com.dalai.llama.preprod.repository.ShotBackgroundMusicRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Background music, on demand only -- never part of the automatic shot-dispatch pipeline (the
 * creator's own explicit call, one track per shot). Sourced from the shot's already-planned
 * {@code Shot.soundDesign} ({@code ambient_bed} layer descriptions written at shot-list generation
 * time, e.g. "Subtle traditional Indian music with light ambient workshop sounds") rather than
 * asking the creator to write a music prompt from scratch. */
@Service
public class ShotBackgroundMusicService {

    private final ShotRepository shotRepository;
    private final ShotBackgroundMusicRepository shotBackgroundMusicRepository;
    private final MediaAssetService mediaAssetService;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String prefix;
    private final String musicModel;

    public ShotBackgroundMusicService(
            ShotRepository shotRepository,
            ShotBackgroundMusicRepository shotBackgroundMusicRepository,
            MediaAssetService mediaAssetService,
            LlmGatewayClient llmGatewayClient,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.background-music-prefix}") String prefix,
            @Value("${pre-production.llm-gateway.background-music-model}") String musicModel
    ) {
        this.shotRepository = shotRepository;
        this.shotBackgroundMusicRepository = shotBackgroundMusicRepository;
        this.mediaAssetService = mediaAssetService;
        this.llmGatewayClient = llmGatewayClient;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.prefix = prefix;
        this.musicModel = musicModel;
    }

    @Transactional
    public ShotBackgroundMusicView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        String prompt = ambientPrompt(shot);
        if (prompt == null || prompt.isBlank()) {
            throw PreProductionException.badRequest(
                    "Shot " + shot.getShotRef() + " has no ambient_bed sound design planned to generate music from");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("prompt", prompt);
        if (shot.getDurationSeconds() != null) {
            params.put("music_length_ms", Math.max(3000, shot.getDurationSeconds() * 1000));
        }
        LlmGatewayChatResponse response = llmGatewayClient.chat(tenantId.toString(), "shot-bg-music-" + shotId,
                new LlmGatewayChatRequest(musicModel, java.util.List.of(new LlmGatewayMessage("user", prompt)), params, null, null)
                        .withProjectId(shot.getProjectId()));
        if (response == null || response.response() == null || !response.response().startsWith("data:audio")) {
            throw PreProductionException.upstream("llm-gateway returned no music audio for shot " + shotId);
        }
        byte[] audioBytes = decodeAudioDataUri(response.response());

        String objectKey = "%s/%s/%s.mp3".formatted(prefix, shotId, UUID.randomUUID());
        upload(objectKey, audioBytes);

        OffsetDateTime now = OffsetDateTime.now();
        ShotBackgroundMusic music = shotBackgroundMusicRepository.findByShotIdAndTenantId(shotId, tenantId)
                .orElseGet(() -> ShotBackgroundMusic.builder().tenantId(tenantId).shotId(shotId).createdAt(now).build());
        music.setBucket(bucket);
        music.setObjectKey(objectKey);
        music.setPrompt(prompt);
        music.setUpdatedAt(now);
        music = shotBackgroundMusicRepository.save(music);
        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.SHOT_BACKGROUND_MUSIC);
        return toView(music);
    }

    @Transactional(readOnly = true)
    public ShotBackgroundMusicView get(UUID tenantId, UUID shotId) {
        return shotBackgroundMusicRepository.findByShotIdAndTenantId(shotId, tenantId).map(this::toView).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<UUID, ShotBackgroundMusicView> listByShotIds(UUID tenantId, List<UUID> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            return Map.of();
        }

        return shotBackgroundMusicRepository.findByTenantIdAndShotIdIn(tenantId, shotIds).stream()
                .collect(Collectors.toMap(
                        ShotBackgroundMusic::getShotId,
                        this::toView
                ));
    }
    /** Joins every {@code ambient_bed} layer's description from {@code Shot.soundDesign} (a JSON
     * array written at shot-list generation time, e.g. {@code [{"layerType": "ambient_bed",
     * "description": "..."}]}) -- {@code sync_hit} layers are one-off foley cues, not background
     * music, so they're deliberately excluded. Null/blank/unparseable soundDesign yields null, not
     * an exception -- the caller turns that into a clear 400 instead. */
    private String ambientPrompt(Shot shot) {
        if (shot.getSoundDesign() == null || shot.getSoundDesign().isBlank()) {
            return null;
        }
        try {
            JsonNode layers = objectMapper.readTree(shot.getSoundDesign());
            if (!layers.isArray()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode layer : layers) {
                if ("ambient_bed".equals(layer.path("layerType").asText(null))) {
                    String description = layer.path("description").asText(null);
                    if (description != null && !description.isBlank()) {
                        if (sb.length() > 0) {
                            sb.append(". ");
                        }
                        sb.append(description);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] decodeAudioDataUri(String dataUri) {
        int comma = dataUri.indexOf(',');
        return Base64.getDecoder().decode(comma < 0 ? dataUri : dataUri.substring(comma + 1));
    }

    private void upload(String objectKey, byte[] bytes) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("audio/mpeg")
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload background music to MinIO: " + ex.getMessage());
        }
    }

    private ShotBackgroundMusicView toView(ShotBackgroundMusic music) {
        return new ShotBackgroundMusicView(music.getId(), signedUrl(music.getBucket(), music.getObjectKey()),
                music.getPrompt(), music.getCreatedAt());
    }

    private String signedUrl(String bucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign background music URL: " + ex.getMessage());
        }
    }
}
