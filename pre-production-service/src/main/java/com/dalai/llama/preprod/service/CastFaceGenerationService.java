package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On-demand face-image generation for a {@link CastProfile}. Answers the "AI-generated cast
 * identities should have images" bug -- a creator now has a "Generate face" button on each
 * character card that, for a HUMAN/NARRATOR-typed profile without an uploaded face, calls the
 * image model with a portrait prompt grounded in the profile's gender/age/description/look, then
 * saves the bytes into MinIO under the cast-media prefix and stamps face_ref_bucket +
 * face_ref_object_key onto the profile so downstream shot-image generation picks it up as an
 * identity reference the same way an uploaded face would.
 *
 * <p>Small deliberate divergences from {@link ShotImageService}'s generate path:
 * <ul>
 *   <li>No aspect-ratio validation -- a portrait is a portrait; the model's default is fine.
 *   <li>No vision-analysis annotation call -- the profile picks up the face-ref, and any shot that
 *       later uses this profile runs its own image analysis on the produced shot image, not on
 *       this cast portrait.
 *   <li>Uploaded under the existing cast-media prefix (same one {@link CastMediaUploadService}
 *       uses for hand-uploaded faces) so the storage layout stays uniform.
 * </ul>
 */
@Slf4j
@Service
public class CastFaceGenerationService {

    private final CastProfileRepository castProfileRepository;
    private final CastProfileService castProfileService;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient minioClient;
    private final String defaultImageModel;
    private final String bucket;
    private final String castMediaPrefix;

    public CastFaceGenerationService(
            CastProfileRepository castProfileRepository,
            CastProfileService castProfileService,
            LlmGatewayClient llmGatewayClient,
            MinioClient minioClient,
            @Value("${pre-production.llm-gateway.default-image-model}") String defaultImageModel,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.cast-media-prefix}") String castMediaPrefix
    ) {
        this.castProfileRepository = castProfileRepository;
        this.castProfileService = castProfileService;
        this.llmGatewayClient = llmGatewayClient;
        this.minioClient = minioClient;
        this.defaultImageModel = defaultImageModel;
        this.bucket = bucket;
        this.castMediaPrefix = castMediaPrefix;
    }

    @Transactional
    public CastProfileView generateFace(UUID tenantId, UUID castProfileId) {
        CastProfile profile = castProfileService.requireCastProfile(tenantId, castProfileId);
        String prompt = buildPrompt(profile);
        // Portrait-shaped, PNG. Aspect handled at prompt-shape level; the vertical bias also
        // matches how downstream shot images use the face-ref as an identity anchor.
        Map<String, Object> params = Map.of(
                "response_format", "image",
                "aspect_ratio", "3:4"
        );
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "cast-face-" + castProfileId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultImageModel,
                        List.of(new LlmGatewayMessage("user", prompt)),
                        params, null, null));

        DecodedImage decoded = decode(response);
        String objectKey = "%s/generated-face/%s.%s".formatted(castMediaPrefix, UUID.randomUUID(), decoded.extension());
        upload(objectKey, decoded);

        profile.setFaceRefBucket(bucket);
        profile.setFaceRefObjectKey(objectKey);
        profile.setUpdatedAt(OffsetDateTime.now());
        castProfileRepository.save(profile);
        log.info("Generated cast face castProfileId={} tenantId={} objectKey={}",
                castProfileId, tenantId, objectKey);
        return castProfileService.toViewForExternalCallers(profile);
    }

    /** Portrait prompt built from whatever identity fields the profile actually has -- gender/
     * age/description/look each contribute if present so a sparsely-filled profile still gets a
     * usable prompt instead of a blank one. Deliberately generic ("photorealistic head-and-
     * shoulders portrait") so the same prompt shape works whether the profile is an actor
     * placeholder or a real-person casting reference. */
    private String buildPrompt(CastProfile profile) {
        StringBuilder sb = new StringBuilder("Photorealistic head-and-shoulders portrait photograph");
        if (profile.getGender() != null && !profile.getGender().isBlank()) {
            sb.append(" of a ").append(profile.getGender().toLowerCase()).append(" person");
        } else {
            sb.append(" of a person");
        }
        if (profile.getAge() != null) {
            sb.append(", around ").append(profile.getAge()).append(" years old");
        }
        if (profile.getDescription() != null && !profile.getDescription().isBlank()) {
            sb.append(". ").append(profile.getDescription().trim());
        }
        sb.append(". Neutral background, natural lighting, direct eye contact, professional casting reference photo, no text, no watermark.");
        return sb.toString();
    }

    private DecodedImage decode(LlmGatewayChatResponse response) {
        String content = response == null ? null : response.response();
        if (content == null || !content.startsWith("data:")) {
            String reason = response != null && response.finishReason() != null
                    ? " (finishReason=" + response.finishReason() + ")" : "";
            throw PreProductionException.upstream("llm-gateway did not return an image for cast-face generation" + reason);
        }
        int comma = content.indexOf(',');
        String header = content.substring(5, content.indexOf(';'));
        String extension = header.contains("/") ? header.substring(header.indexOf('/') + 1) : "png";
        byte[] bytes = Base64.getDecoder().decode(content.substring(comma + 1));
        return new DecodedImage(bytes, "image/" + extension, extension);
    }

    private void upload(String objectKey, DecodedImage decoded) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(decoded.bytes())) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, decoded.bytes().length, -1)
                    .contentType(decoded.contentType())
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload generated cast face to MinIO: " + ex.getMessage());
        }
    }

    private record DecodedImage(byte[] bytes, String contentType, String extension) {}
}
