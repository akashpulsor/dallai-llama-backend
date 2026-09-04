package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.ProductReferenceClassification;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import com.dalai.llama.preprod.dto.AnalyzeShotProductReferenceView;
import com.dalai.llama.preprod.dto.ConfirmShotProductReferenceRequest;
import com.dalai.llama.preprod.dto.ShotProductReferenceView;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ShotProductReferenceAnalysisResult;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The "attach a real-world reference photo to a shot" flow -- analyze (one vision call, nothing
 * persisted) then confirm (upsert, one reference per shot). Mirrors creator-service's real
 * analyze/confirm split: CAST means the photo IS the subject to render (identity preserved
 * exactly); INSPIRATION means the photo only lends mood/composition/lighting (subject/branding
 * never copied). Both classifications reuse the exact same upload + vision-call plumbing, just a
 * different prompt task key and a different subset of the parsed result.
 */
@Service
public class ShotProductReferenceService {

    private static final String TASK_KEY_CAST = "PRE_PROD_PRODUCT_REFERENCE_CAST_DESCRIBE";
    private static final String TASK_KEY_INSPIRATION = "PRE_PROD_PRODUCT_REFERENCE_INSPIRATION_ANALYZE";

    private final ShotRepository shotRepository;
    private final ShotProductReferenceRepository shotProductReferenceRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final MediaAssetService mediaAssetService;
    private final ObjectMapper objectMapper;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final String bucket;
    private final String referencePrefix;
    private final String defaultModel;

    public ShotProductReferenceService(
            ShotRepository shotRepository,
            ShotProductReferenceRepository shotProductReferenceRepository,
            LlmGatewayClient llmGatewayClient,
            MediaAssetService mediaAssetService,
            ObjectMapper objectMapper,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.cast-media-prefix}") String referencePrefix,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.shotProductReferenceRepository = shotProductReferenceRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.mediaAssetService = mediaAssetService;
        this.objectMapper = objectMapper;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.bucket = bucket;
        this.referencePrefix = referencePrefix;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public AnalyzeShotProductReferenceView analyze(UUID tenantId, UUID shotId, ProductReferenceClassification classification, MultipartFile file) {
        requireShot(tenantId, shotId);
        if (file == null || file.isEmpty()) {
            throw PreProductionException.badRequest("No file was uploaded");
        }
        String contentType = file.getContentType() == null ? "image/jpeg" : file.getContentType();
        String dataUri = toDataUri(file, contentType);

        String objectKey = "%s/product-references/%s/%s.%s".formatted(referencePrefix, shotId, UUID.randomUUID(), extensionFor(contentType));
        upload(objectKey, file, contentType);

        ShotProductReferenceAnalysisResult parsed = classification == ProductReferenceClassification.CAST
                ? analyzeCast(tenantId, shotId, dataUri)
                : analyzeInspiration(tenantId, shotId, dataUri);

        return new AnalyzeShotProductReferenceView(bucket, objectKey, classification,
                parsed.personDescription(), parsed.detectedSubject(), parsed.dominantMood(),
                parsed.cameraAngle(), parsed.lightingStyle(), parsed.motion());
    }

    @Transactional
    public ShotProductReferenceView confirm(UUID tenantId, UUID shotId, ConfirmShotProductReferenceRequest request) {
        requireShot(tenantId, shotId);
        OffsetDateTime now = OffsetDateTime.now();
        ShotProductReference reference = shotProductReferenceRepository.findByShotId(shotId).orElseGet(() -> ShotProductReference.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .createdAt(now)
                .build());
        reference.setClassification(request.classification());
        reference.setBucket(request.bucket());
        reference.setObjectKey(request.objectKey());
        reference.setPersonDescription(request.personDescription());
        reference.setDetectedSubject(request.detectedSubject());
        reference.setDominantMood(request.dominantMood());
        reference.setReferenceCameraAngle(request.referenceCameraAngle());
        reference.setReferenceLightingStyle(request.referenceLightingStyle());
        reference.setReferenceMotion(request.referenceMotion());
        reference.setIgnoreSubject(Boolean.TRUE.equals(request.ignoreSubject()));
        reference.setUpdatedAt(now);
        reference = shotProductReferenceRepository.save(reference);

        mediaAssetService.registerIfAbsent(tenantId, request.bucket(), request.objectKey(), MediaAssetType.PRODUCT_REFERENCE);
        return toView(reference);
    }

    @Transactional(readOnly = true)
    public ShotProductReferenceView get(UUID tenantId, UUID shotId) {
        requireShot(tenantId, shotId);
        return shotProductReferenceRepository.findByShotId(shotId)
                .map(this::toView)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no product reference yet"));
    }

    private ShotProductReferenceAnalysisResult analyzeCast(UUID tenantId, UUID shotId, String dataUri) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "shot-product-reference-cast-" + shotId,
                new LlmGatewayChatRequest(defaultModel,
                        List.of(new LlmGatewayMessage("user", "Describe the person or product in this reference photo.", List.of(dataUri))),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY_CAST, Map.of()));
        return parse(response, TASK_KEY_CAST);
    }

    private ShotProductReferenceAnalysisResult analyzeInspiration(UUID tenantId, UUID shotId, String dataUri) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "shot-product-reference-inspiration-" + shotId,
                new LlmGatewayChatRequest(defaultModel,
                        List.of(new LlmGatewayMessage("user", "Analyze this style/mood reference photo.", List.of(dataUri))),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY_INSPIRATION, Map.of()));
        return parse(response, TASK_KEY_INSPIRATION);
    }

    private ShotProductReferenceAnalysisResult parse(LlmGatewayChatResponse response, String taskKey) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + taskKey);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ShotProductReferenceAnalysisResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + taskKey + " response as JSON: " + ex.getMessage());
        }
    }

    private void requireShot(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId).orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
    }

    private String toDataUri(MultipartFile file, String contentType) {
        try {
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception ex) {
            throw PreProductionException.badRequest("Could not read uploaded image: " + ex.getMessage());
        }
    }

    private String extensionFor(String contentType) {
        int slash = contentType.indexOf('/');
        return slash < 0 ? "jpg" : contentType.substring(slash + 1);
    }

    private void upload(String objectKey, MultipartFile file, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload product reference to MinIO: " + ex.getMessage());
        }
    }

    private String signedUrl(String sourceBucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(sourceBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign product reference URL: " + ex.getMessage());
        }
    }

    private ShotProductReferenceView toView(ShotProductReference reference) {
        return new ShotProductReferenceView(reference.getId(), reference.getShotId(), reference.getClassification(),
                reference.getBucket(), reference.getObjectKey(), signedUrl(reference.getBucket(), reference.getObjectKey()),
                reference.getPersonDescription(), reference.getDetectedSubject(), reference.getDominantMood(),
                reference.getReferenceCameraAngle(), reference.getReferenceLightingStyle(), reference.getReferenceMotion(),
                reference.getIgnoreSubject());
    }
}
