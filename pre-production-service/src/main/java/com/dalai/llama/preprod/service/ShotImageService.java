package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.CameraPlan;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.ShotImagePromptBuilder;
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
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Generates all 4 shot image kinds (see {@link ShotImageKind}) -- what used to be
 * StoryboardImageService's single STORYBOARD-only path. STORYBOARD keeps using {@code
 * Shot.sketchPrompt} verbatim (captured once at shot-list time); the other 3 kinds build their
 * prompt deterministically from the shot's own fields via {@link ShotImagePromptBuilder} (no
 * separate LLM call to write the prompt itself).
 *
 * <p>Identity conditioning (PRODUCTION only, when the shot's primary character has a resolved
 * {@code CastProfile}) is just a different {@code modelId} -- {@code identityImageModel} instead
 * of the plain {@code imageModel} -- plus the reference photo's signed URL in {@code
 * params.reference_image_urls}. Which provider/technique actually handles that model id (fal.ai's
 * FLUX_PULID today) is llm-gateway's concern entirely, the same way {@code default-text-model}/
 * {@code default-image-model} already are for every other generation call in this service; this
 * class never branches on provider or technique.
 */
@Service
public class ShotImageService {

    private final ShotRepository shotRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final CastProfileRepository castProfileRepository;
    private final ShotImageRepository shotImageRepository;
    private final ShotProductReferenceRepository shotProductReferenceRepository;
    private final LightingPlanRepository lightingPlanRepository;
    private final CameraPlanRepository cameraPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final MediaAssetService mediaAssetService;
    private final GenerationThoughtService generationThoughtService;
    private final MinioClient minioClient;
    private final String bucket;
    private final String storyboardPrefix;
    private final String imageModel;
    private final String identityImageModel;

    public ShotImageService(
            ShotRepository shotRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            CastAssignmentRepository castAssignmentRepository,
            CastProfileRepository castProfileRepository,
            ShotImageRepository shotImageRepository,
            ShotProductReferenceRepository shotProductReferenceRepository,
            LightingPlanRepository lightingPlanRepository,
            CameraPlanRepository cameraPlanRepository,
            LlmGatewayClient llmGatewayClient,
            MediaAssetService mediaAssetService,
            GenerationThoughtService generationThoughtService,
            MinioClient minioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.storyboard-prefix}") String storyboardPrefix,
            @Value("${pre-production.llm-gateway.default-image-model}") String imageModel,
            @Value("${pre-production.llm-gateway.identity-image-model}") String identityImageModel
    ) {
        this.shotRepository = shotRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.castProfileRepository = castProfileRepository;
        this.shotImageRepository = shotImageRepository;
        this.shotProductReferenceRepository = shotProductReferenceRepository;
        this.lightingPlanRepository = lightingPlanRepository;
        this.cameraPlanRepository = cameraPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.mediaAssetService = mediaAssetService;
        this.generationThoughtService = generationThoughtService;
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.storyboardPrefix = storyboardPrefix;
        this.imageModel = imageModel;
        this.identityImageModel = identityImageModel;
    }

    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind) {
        return generate(tenantId, shotId, kind, null);
    }

    /** {@code note} is a creator- or client-suggested change (see ChangeRequest) folded into the
     * prompt verbatim -- e.g. "make the lighting warmer". Null for a plain (re)generate. */
    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind, String note) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        CastProfile castProfile = kind == ShotImageKind.PRODUCTION ? resolveCastProfile(tenantId, shot) : null;
        ShotProductReference productReference = kind == ShotImageKind.PRODUCTION
                ? shotProductReferenceRepository.findByShotId(shotId).orElse(null) : null;

        String prompt = promptFor(shot, kind, castProfile, productReference);
        if (prompt == null || prompt.isBlank()) {
            throw PreProductionException.badRequest(
                    "Shot " + shotId + " has no " + kind + " prompt available yet -- generate the shot list first");
        }
        if (note != null && !note.isBlank()) {
            prompt = prompt + "\n\nRequested change: " + note;
        }

        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_STARTED", "Generating " + kind + " image");

        String modelId;
        Map<String, Object> params;
        if (productReference != null) {
            modelId = identityImageModel;
            params = Map.of("reference_image_urls", List.of(signedUrl(productReference.getBucket(), productReference.getObjectKey())));
        } else if (castProfile != null) {
            modelId = identityImageModel;
            params = Map.of("reference_image_urls", List.of(signedUrl(castProfile.getFaceRefBucket(), castProfile.getFaceRefObjectKey())));
        } else {
            modelId = imageModel;
            params = Map.of("response_format", "image");
        }

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "shot-image-" + kind.name().toLowerCase() + "-" + shotId,
                new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", prompt)), params, null, null));

        DecodedImage decoded = decode(response, kind);
        String objectKey = "%s/%s/%s/%s.%s".formatted(storyboardPrefix, kind.name().toLowerCase(), shotId, UUID.randomUUID(), decoded.extension());
        upload(objectKey, decoded);

        OffsetDateTime now = OffsetDateTime.now();
        ShotImage image = shotImageRepository.findByShotIdAndKind(shotId, kind).orElseGet(() -> ShotImage.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .kind(kind)
                .createdAt(now)
                .build());
        image.setBucket(bucket);
        image.setObjectKey(objectKey);
        image.setPrompt(prompt);
        image.setReferenceCastProfileId(castProfile == null ? null : castProfile.getId());
        image.setUpdatedAt(now);
        image = shotImageRepository.save(image);

        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.STORYBOARD_IMAGE);
        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_GENERATED", kind + " image stored at " + objectKey);

        return toView(image);
    }

    @Transactional(readOnly = true)
    public ShotImageView get(UUID tenantId, UUID shotId, ShotImageKind kind) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        ShotImage image = shotImageRepository.findByShotIdAndKind(shotId, kind)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no " + kind + " image yet"));
        return toView(image);
    }

    @Transactional(readOnly = true)
    public List<ShotImageView> list(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        return shotImageRepository.findByShotId(shotId).stream().map(this::toView).collect(Collectors.toList());
    }

    private String promptFor(Shot shot, ShotImageKind kind, CastProfile castProfile, ShotProductReference productReference) {
        return switch (kind) {
            case STORYBOARD -> shot.getSketchPrompt();
            case PRODUCTION -> ShotImagePromptBuilder.buildProductionPrompt(shot, castProfile, productReference);
            case LIGHTING -> ShotImagePromptBuilder.buildLightingSheetPrompt(shot, lightingPlanRepository.findByShotId(shot.getId()).orElse(null));
            case CAMERA_PLAN -> ShotImagePromptBuilder.buildCameraPlanSheetPrompt(shot, cameraPlanRepository.findByShotId(shot.getId()).orElse(null));
        };
    }

    /** Same resolution {@code ShotContextAssemblyService} does for dispatch -- duplicated rather
     * than shared because that class resolves a whole {@code ShotAssemblyContext}, this only
     * needs the profile. */
    private CastProfile resolveCastProfile(UUID tenantId, Shot shot) {
        if (shot.getPrimaryCharacterKey() == null) {
            return null;
        }
        Script script = scriptRepository.findByProjectId(shot.getProjectId()).orElse(null);
        if (script == null) {
            return null;
        }
        ScriptCharacter character = scriptCharacterRepository
                .findByScriptIdAndCharacterKey(script.getId(), shot.getPrimaryCharacterKey()).orElse(null);
        if (character == null) {
            return null;
        }
        CastAssignment assignment = castAssignmentRepository
                .findByProjectIdAndScriptCharacterId(shot.getProjectId(), character.getId()).orElse(null);
        if (assignment == null) {
            return null;
        }
        return castProfileRepository.findByIdAndTenantId(assignment.getCastProfileId(), tenantId).orElse(null);
    }

    private DecodedImage decode(LlmGatewayChatResponse response, ShotImageKind kind) {
        String content = response == null ? null : response.response();
        if (content == null || !content.startsWith("data:")) {
            // fal.ai's "image" modelType returns a plain URL, not a data URI (see
            // FalAiProvider.firstImageUrl) -- llm-gateway's own LlmResponse.content carries
            // whichever shape the provider returned, so a URL result is downloaded here rather
            // than base64-decoded, then handled identically from that point on.
            if (content != null && (content.startsWith("http://") || content.startsWith("https://"))) {
                return download(content);
            }
            throw PreProductionException.upstream("llm-gateway did not return an image for " + kind + " shot image generation");
        }
        int comma = content.indexOf(',');
        String header = content.substring(5, content.indexOf(';'));
        String extension = header.contains("/") ? header.substring(header.indexOf('/') + 1) : "png";
        byte[] bytes = Base64.getDecoder().decode(content.substring(comma + 1));
        return new DecodedImage(bytes, "image/" + extension, extension);
    }

    private DecodedImage download(String url) {
        try (var in = new java.net.URL(url).openStream()) {
            byte[] bytes = in.readAllBytes();
            String extension = url.contains(".") ? url.substring(url.lastIndexOf('.') + 1).split("[?#]")[0] : "jpg";
            return new DecodedImage(bytes, "image/" + extension, extension);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not download generated image from " + url + ": " + ex.getMessage());
        }
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
            throw PreProductionException.upstream("Could not upload shot image to MinIO: " + ex.getMessage());
        }
    }

    private String signedUrl(String sourceBucket, String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(sourceBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign reference image URL: " + ex.getMessage());
        }
    }

    private ShotImageView toView(ShotImage image) {
        return new ShotImageView(image.getId(), image.getKind(), image.getBucket(), image.getObjectKey(),
                signedUrl(image.getBucket(), image.getObjectKey()), image.getCreatedAt());
    }

    private record DecodedImage(byte[] bytes, String contentType, String extension) {
    }
}
