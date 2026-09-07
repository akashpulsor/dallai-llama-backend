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
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.IdentityPromptRewriteResult;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.ShotImagePromptBuilder;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private final MotionGraphicPlanRepository motionGraphicPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final MediaAssetService mediaAssetService;
    private final GenerationThoughtService generationThoughtService;
    private final ShotImageDescriptionService shotImageDescriptionService;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String storyboardPrefix;
    private final String imageModel;
    private final String identityImageModel;
    private final String defaultTextModel;

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
            MotionGraphicPlanRepository motionGraphicPlanRepository,
            LlmGatewayClient llmGatewayClient,
            MediaAssetService mediaAssetService,
            GenerationThoughtService generationThoughtService,
            ShotImageDescriptionService shotImageDescriptionService,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.storyboard-prefix}") String storyboardPrefix,
            @Value("${pre-production.llm-gateway.default-image-model}") String imageModel,
            @Value("${pre-production.llm-gateway.identity-image-model}") String identityImageModel,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultTextModel
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
        this.motionGraphicPlanRepository = motionGraphicPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.mediaAssetService = mediaAssetService;
        this.generationThoughtService = generationThoughtService;
        this.shotImageDescriptionService = shotImageDescriptionService;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.storyboardPrefix = storyboardPrefix;
        this.imageModel = imageModel;
        this.identityImageModel = identityImageModel;
        this.defaultTextModel = defaultTextModel;
    }

    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind) {
        return generate(tenantId, shotId, kind, null);
    }

    /** {@code note} is a creator- or client-suggested change (see ChangeRequest) folded into the
     * prompt verbatim -- e.g. "make the lighting warmer". Null for a plain (re)generate. */
    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind, String note) {
        return generate(tenantId, shotId, kind, note, List.of());
    }

    /** Same as {@link #generate(UUID, UUID, ShotImageKind, String)}, plus one or more inspiration
     * images (already-downloaded bytes, e.g. from a creator upload) to edit alongside the shot's
     * current image -- e.g. "match this reference photo's lighting". Applies to STORYBOARD,
     * LIGHTING, and CAMERA_PLAN the same way; a PRODUCTION shot with identity conditioning
     * (fal.ai reference_image_urls, a different mechanism entirely) ignores inspiration images. */
    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind, String note, List<String> inspirationDataUris) {
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
        List<String> editDataUris = null;
        if (productReference != null || castProfile != null) {
            modelId = identityImageModel;
            String refBucket = productReference != null ? productReference.getBucket() : castProfile.getFaceRefBucket();
            String refObjectKey = productReference != null ? productReference.getObjectKey() : castProfile.getFaceRefObjectKey();
            if (isGeminiModel(modelId)) {
                // Gemini has no separate "reference image" request param the way fal.ai's
                // FLUX_PULID does -- it conditions on whatever images ride along inline with the
                // prompt, same imageDataUris mechanism the plain-edit path below already uses.
                // Confirmed live: the fal.ai path 502'd with "FAL_API_KEY is not configured" the
                // moment a real batch hit an identity-conditioned shot -- that key was never
                // actually provisioned in this environment, so identity-image-model now defaults
                // to Gemini (see application.yml), which needs no separate credential at all.
                params = imageParams(shot);
                String refDataUri = toDataUri(refBucket, refObjectKey);
                editDataUris = refDataUri == null ? null : List.of(refDataUri);
                prompt = reliabilityRewrite(tenantId, shot.getProjectId(), prompt, identityPronounHint(castProfile, productReference));
            } else {
                params = Map.of("reference_image_urls", List.of(signedUrl(refBucket, refObjectKey)));
            }
        } else {
            modelId = imageModel;
            params = imageParams(shot);
            // A chat-requested change ("make her jacket red") should edit the actual current
            // image, not regenerate blind from text alone -- Gemini's image model accepts multiple
            // input images inline alongside the instruction and edits from them directly (same
            // imageDataUris path ShotImageDescriptionService uses to read an image, just fed into
            // an image-out call here instead of a text-out one). Current image first (what's being
            // edited), then any inspiration image(s) (what to match/borrow from).
            List<String> images = new java.util.ArrayList<>();
            if (note != null && !note.isBlank()) {
                shotImageRepository.findByShotIdAndKind(shotId, kind).map(this::toDataUri).ifPresent(images::add);
            }
            images.addAll(inspirationDataUris);
            editDataUris = images.isEmpty() ? null : images;
        }

        DecodedImage decoded = generateWithRetry(tenantId, shotId, kind, shot, modelId, prompt, editDataUris, params);
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

    /** Entry point for the "pick a reference photo (e.g. from Pinterest), apply its cinematic
     * technique to our shot" flow -- from the creator's Shots-tab chat. Each file is read straight
     * into a data URI (no MinIO round-trip needed; these aren't kept, just fed to the edit call
     * once), analyzed for its lighting/camera/composition/texture/color grade (see {@link
     * ShotImageDescriptionService#analyzeInspiration}) so the model gets explicit style direction
     * -- not just "make it look like this picture" but which qualities of it to copy -- and passed
     * alongside the raw photo itself as a second visual signal. */
    @Transactional
    public ShotImageView generateWithInspiration(UUID tenantId, UUID shotId, ShotImageKind kind, String note, List<MultipartFile> inspirationImages) {
        List<String> dataUris = inspirationImages.stream()
                .map(this::toDataUri)
                .filter(java.util.Objects::nonNull)
                .toList();
        String styleDirection = dataUris.stream()
                .map(uri -> shotImageDescriptionService.analyzeInspiration(tenantId, uri))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("\n"));
        String combinedNote = styleDirection.isBlank()
                ? note
                : (note == null || note.isBlank() ? "" : note + "\n\n") + styleDirection;
        return generate(tenantId, shotId, kind, combinedNote, dataUris);
    }

    private String toDataUri(MultipartFile file) {
        try {
            String contentType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception ex) {
            return null;
        }
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
            case MOTION_GRAPHIC -> ShotImagePromptBuilder.buildMotionGraphicPreviewPrompt(shot, motionGraphicPlanRepository.findByShotId(shot.getId()).orElse(null));
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

    /** Confirmed live: even the corrected identity-lock prompt (see {@link
     * ShotImagePromptBuilder}) doesn't make identity-conditioned Gemini calls 100% reliable --
     * shot 8's PRODUCTION image failed again with finishReason=IMAGE_OTHER on the very first
     * manual retry after that prompt fix shipped, using the exact same (now-corrected) prompt
     * text. This appears to be real per-call flakiness in Gemini's identity-conditioned image
     * generation, not something a prompt wording change alone fully eliminates. A single manual
     * "Generate"/"Regenerate" click used to get exactly one attempt; this gives it up to 3 (a
     * fresh idempotency key each time, so it's a real re-dispatch, not a cached replay) before
     * surfacing a failure -- the same margin the batch worker already gets per step, just inside
     * one call instead of spread across separate dead-letter-tracked attempts. */
    private DecodedImage generateWithRetry(UUID tenantId, UUID shotId, ShotImageKind kind, Shot shot,
            String modelId, String prompt, List<String> editDataUris, Map<String, Object> params) {
        PreProductionException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                LlmGatewayChatResponse response = llmGatewayClient.chat(
                        tenantId.toString(),
                        "shot-image-" + kind.name().toLowerCase() + "-" + shotId + "-" + UUID.randomUUID(),
                        new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", prompt, editDataUris)), params, null, null)
                                .withProjectId(shot.getProjectId()));
                DecodedImage decoded = decode(response, kind);
                validateAspectRatio(decoded, shot, kind);
                return decoded;
            } catch (PreProductionException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure;
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
            String reason = response != null && response.finishReason() != null ? " (finishReason=" + response.finishReason() + ")" : "";
            throw PreProductionException.upstream("llm-gateway did not return an image for " + kind + " shot image generation" + reason);
        }
        int comma = content.indexOf(',');
        String header = content.substring(5, content.indexOf(';'));
        String extension = header.contains("/") ? header.substring(header.indexOf('/') + 1) : "png";
        byte[] bytes = Base64.getDecoder().decode(content.substring(comma + 1));
        return new DecodedImage(bytes, "image/" + extension, extension);
    }

    /** Confirmed live (see the batch-run investigation this guards against): a prose-only aspect
     * ratio instruction in the prompt is not reliable -- the model can silently return a
     * differently-shaped image (square instead of the requested 9:16) with no error at all, which
     * previously shipped straight to storage unnoticed. This makes that failure loud instead of
     * silent: a shape well outside what was requested now fails the generation, which for the
     * batch worker means it retries (a different attempt can land on the right shape) then
     * dead-letters instead of quietly publishing a wrong-shape asset. PNG-only (every Gemini image
     * response is PNG) and a generous 20% tolerance, since Gemini rounds to its own nearest
     * supported output resolution (e.g. 768x1344 for a requested 9:16, not an exact 0.5625) rather
     * than cropping to the exact ratio. */
    private void validateAspectRatio(DecodedImage image, Shot shot, ShotImageKind kind) {
        String ratio = geminiAspectRatio(shot.getAspectRatio());
        if (ratio == null || !"png".equalsIgnoreCase(image.extension()) || image.bytes().length < 24) {
            return;
        }
        byte[] bytes = image.bytes();
        boolean isPng = bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        if (!isPng) {
            return;
        }
        long width = readUInt32BE(bytes, 16);
        long height = readUInt32BE(bytes, 20);
        if (width <= 0 || height <= 0) {
            return;
        }
        String[] parts = ratio.split(":");
        double expected = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        double actual = (double) width / height;
        double relativeError = Math.abs(actual - expected) / expected;
        if (relativeError > 0.20) {
            throw PreProductionException.upstream(
                    "Generated %s image for shot %s has the wrong aspect ratio -- expected %s (%.3f) but got %dx%d (%.3f)"
                            .formatted(kind, shot.getId(), ratio, expected, width, height, actual));
        }
    }

    private long readUInt32BE(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL) << 24) | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8) | (bytes[offset + 3] & 0xFFL);
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

    /** Downloads an existing shot image and wraps it as a {@code data:} URI so it can ride along
     * as edit input on the next generation call -- best-effort, same as {@link
     * com.dalai.llama.preprod.service.ShotImageDescriptionService}: an unreadable image degrades to
     * a plain text-only regenerate rather than failing the whole apply. */
    private String toDataUri(ShotImage image) {
        return toDataUri(image.getBucket(), image.getObjectKey());
    }

    /** Same idea, for a reference image living outside this shot's own bucket (a CastProfile face
     * ref or a ShotProductReference photo) -- what identity conditioning needs when routed through
     * Gemini instead of fal.ai (see the identity-conditioning branch in {@link #generate}). */
    private String toDataUri(String bucket, String objectKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            String lower = objectKey.toLowerCase();
            String mimeType = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception ex) {
            return null;
        }
    }

    /** {@code identity-image-model} stays configurable (a deployment with a real fal.ai key can
     * still point it at flux-pulid-v1 and get the reference_image_urls behavior below), but the
     * two providers take a reference image completely differently -- Gemini inline, fal.ai as a
     * request param -- so this is the one place that has to know which shape the configured
     * model actually wants. */
    private boolean isGeminiModel(String modelId) {
        return modelId != null && modelId.startsWith("gemini");
    }

    private String identityPronounHint(CastProfile castProfile, ShotProductReference productReference) {
        if (castProfile != null) {
            String gender = castProfile.getGender() == null ? "" : castProfile.getGender().trim().toLowerCase();
            if (gender.startsWith("f")) {
                return "her";
            }
            if (gender.startsWith("m")) {
                return "his";
            }
            return "their";
        }
        if (productReference != null && productReference.getPersonDescription() != null && !productReference.getPersonDescription().isBlank()) {
            return "their";
        }
        return "not a person -- this is a product/object, do not use a person pronoun";
    }

    /** See PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE -- a text model rewrites the already-
     * assembled identity-conditioned prompt for natural, non-absolutist phrasing before it goes to
     * the image model (see {@link ShotImagePromptBuilder} for the confirmed live evidence this is
     * based on). Optional hardening, not a correctness dependency: any failure here (parse error,
     * upstream error, blank response) falls back to the original candidate prompt rather than
     * blocking generation -- a worse-phrased prompt is still better than no attempt at all. */
    private String reliabilityRewrite(UUID tenantId, UUID projectId, String candidatePrompt, String pronoun) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "shot-image-identity-rewrite-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(defaultTextModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, "PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE",
                            Map.of("candidatePrompt", candidatePrompt, "pronoun", pronoun))
                            .withProjectId(projectId));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return candidatePrompt;
            }
            IdentityPromptRewriteResult result = objectMapper.readValue(
                    JsonExtraction.stripCodeFence(response.response()), IdentityPromptRewriteResult.class);
            return result.prompt() == null || result.prompt().isBlank() ? candidatePrompt : result.prompt();
        } catch (Exception ex) {
            return candidatePrompt;
        }
    }

    /** {@code response_format=image} plus a structural {@code aspect_ratio} (see
     * GoogleGeminiProvider's {@code imageConfig.aspectRatio}) -- without the latter, aspect ratio
     * was only ever prose in the prompt text ("9:16 composition"), which Gemini isn't obligated to
     * honor and confirmed live often didn't, defaulting to square/landscape regardless of what the
     * shot was actually configured for. */
    private Map<String, Object> imageParams(Shot shot) {
        String ratio = geminiAspectRatio(shot.getAspectRatio());
        return ratio == null
                ? Map.of("response_format", "image")
                : Map.of("response_format", "image", "aspect_ratio", ratio);
    }

    private String geminiAspectRatio(com.dalai.llama.preprod.domain.AspectRatio aspectRatio) {
        if (aspectRatio == null) {
            return null;
        }
        return switch (aspectRatio) {
            case RATIO_16_9 -> "16:9";
            case RATIO_9_16 -> "9:16";
            case RATIO_1_1 -> "1:1";
            case RATIO_4_5 -> "4:5";
            case RATIO_21_9 -> "21:9";
        };
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
