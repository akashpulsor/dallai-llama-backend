package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The "VLM caption" half of a real multimodal-RAG setup for shot images: an image embedding alone
 * can answer "find similar images", never "what color is her dress" -- that needs the model to
 * actually look at the pixels. This asks a vision-capable model (the same default text model
 * every other stage here uses -- Gemini's flash tier takes image input, not just image output) to
 * describe one already-generated image in concrete detail, as plain text. That text is what
 * {@link ProjectLockService} folds into the shot's chat-service ingestion -- chat-service's own
 * embedding/RAG pipeline is the "vector DB" layer; this service only produces the description text
 * that pipeline embeds and later retrieves. Never fails the caller: an unreadable/undecodable image
 * degrades to no description, not a broken shot list. */
@Service
public class ShotImageDescriptionService {

    private static final String DESCRIBE_TASK_KEY = "PRE_PROD_IMAGE_DESCRIBE";
    private static final String INSPIRATION_TASK_KEY = "PRE_PROD_INSPIRATION_ANALYZE";

    private final MinioClient minioClient;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ShotImageDescriptionService(
            MinioClient minioClient,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.minioClient = minioClient;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    public String describe(UUID tenantId, ShotImage image) {
        try {
            byte[] bytes = downloadBytes(image.getBucket(), image.getObjectKey());
            String mimeType = mimeTypeFor(image.getObjectKey());
            String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            String response = call(tenantId, "shot-image-describe-" + image.getId(), dataUri, DESCRIBE_TASK_KEY);
            if (response == null) {
                return null;
            }
            DescribeResult parsed = objectMapper.readValue(response, DescribeResult.class);
            return parsed.description();
        } catch (Exception ex) {
            return null;
        }
    }

    /** "Pick a reference photo, figure out its cinematic technique, apply it to our own shot" --
     * the analysis half of the inspiration-image flow (see {@code
     * ShotImageService#generateWithInspiration}). Folds the structured breakdown into one
     * plain-text style-direction block to append to the next generation prompt; never fails the
     * caller -- an unreadable reference photo just means no extra style direction, the raw image
     * (still passed separately as inlineData) is the fallback signal. */
    public String analyzeInspiration(UUID tenantId, String dataUri) {
        try {
            String response = call(tenantId, "inspiration-analyze-" + UUID.randomUUID(), dataUri, INSPIRATION_TASK_KEY);
            if (response == null) {
                return null;
            }
            InspirationAnalysis a = objectMapper.readValue(response, InspirationAnalysis.class);
            return "Match this reference's cinematic technique -- lighting: " + a.lighting()
                    + "; camera: " + a.cameraAngle() + "; composition: " + a.composition()
                    + "; texture: " + a.texture() + "; color grade: " + a.colorGrade() + "; mood: " + a.mood();
        } catch (Exception ex) {
            return null;
        }
    }

    private String call(UUID tenantId, String idempotencyKey, String dataUri, String taskKey) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                idempotencyKey,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "", List.of(dataUri))),
                        JsonExtraction.JSON_MODE_PARAMS, taskKey, Map.of()));
        if (response == null || response.response() == null || response.response().isBlank()) {
            return null;
        }
        return JsonExtraction.stripCodeFence(response.response());
    }

    private byte[] downloadBytes(String bucket, String objectKey) throws Exception {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        }
    }

    private String mimeTypeFor(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DescribeResult(String description, String onScreenText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InspirationAnalysis(
            String lighting, String cameraAngle, String composition, String texture, String colorGrade, String mood) {
    }
}
