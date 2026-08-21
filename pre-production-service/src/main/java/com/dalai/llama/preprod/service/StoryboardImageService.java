package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.StoryboardImageView;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Turns a shot's {@code sketchPrompt} (already captured during shot-list generation) into a
 * stored storyboard image -- closes the design doc's storyboard-image-generation gap by reusing
 * llm-gateway's image-typed Gemini route (see {@code GoogleGeminiProvider}'s {@code
 * response_format=image} handling), the same account/adapter creator-service's own
 * StoryboardImageGenerationService already uses in production.
 */
@Service
public class StoryboardImageService {

    private final ShotRepository shotRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient minioClient;
    private final MediaAssetService mediaAssetService;
    private final GenerationThoughtService generationThoughtService;
    private final String bucket;
    private final String storyboardPrefix;
    private final String imageModel;

    public StoryboardImageService(
            ShotRepository shotRepository,
            LlmGatewayClient llmGatewayClient,
            MinioClient minioClient,
            MediaAssetService mediaAssetService,
            GenerationThoughtService generationThoughtService,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.storyboard-prefix}") String storyboardPrefix,
            @Value("${pre-production.llm-gateway.default-image-model}") String imageModel
    ) {
        this.shotRepository = shotRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.minioClient = minioClient;
        this.mediaAssetService = mediaAssetService;
        this.generationThoughtService = generationThoughtService;
        this.bucket = bucket;
        this.storyboardPrefix = storyboardPrefix;
        this.imageModel = imageModel;
    }

    @Transactional
    public StoryboardImageView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (shot.getSketchPrompt() == null || shot.getSketchPrompt().isBlank()) {
            throw PreProductionException.badRequest("Shot " + shotId + " has no sketchPrompt yet -- generate the shot list first");
        }

        generationThoughtService.log(tenantId, shotId, "STORYBOARD_IMAGE_STARTED", "Generating storyboard image from sketch prompt");
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "storyboard-image-" + shotId,
                new LlmGatewayChatRequest(imageModel, List.of(new LlmGatewayMessage("user", shot.getSketchPrompt())),
                        Map.of("response_format", "image"), null, null));

        DecodedImage decoded = decode(response);
        String objectKey = "%s/%s/%s.%s".formatted(storyboardPrefix, shotId, UUID.randomUUID(), decoded.extension());
        upload(objectKey, decoded);

        shot.setStoryboardImageBucket(bucket);
        shot.setStoryboardImageObjectKey(objectKey);
        shotRepository.save(shot);
        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.STORYBOARD_IMAGE);
        generationThoughtService.log(tenantId, shotId, "STORYBOARD_IMAGE_GENERATED", "Storyboard image stored at " + objectKey);

        return new StoryboardImageView(bucket, objectKey, signedUrl(objectKey));
    }

    @Transactional(readOnly = true)
    public StoryboardImageView get(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (shot.getStoryboardImageObjectKey() == null) {
            throw PreProductionException.notFound("Shot " + shotId + " has no storyboard image yet");
        }
        return new StoryboardImageView(shot.getStoryboardImageBucket(), shot.getStoryboardImageObjectKey(),
                signedUrl(shot.getStoryboardImageObjectKey()));
    }

    private DecodedImage decode(LlmGatewayChatResponse response) {
        String content = response == null ? null : response.response();
        if (content == null || !content.startsWith("data:")) {
            throw PreProductionException.upstream("llm-gateway did not return an image data URI for storyboard generation");
        }
        int comma = content.indexOf(',');
        String header = content.substring(5, content.indexOf(';'));
        String extension = header.contains("/") ? header.substring(header.indexOf('/') + 1) : "png";
        byte[] bytes = Base64.getDecoder().decode(content.substring(comma + 1));
        return new DecodedImage(bytes, "image/" + extension, extension);
    }

    private void upload(String objectKey, DecodedImage image) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(image.bytes()), image.bytes().length, -1)
                    .contentType(image.contentType())
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload storyboard image to MinIO: " + ex.getMessage());
        }
    }

    private String signedUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign storyboard image URL: " + ex.getMessage());
        }
    }

    private record DecodedImage(byte[] bytes, String contentType, String extension) {
    }
}
