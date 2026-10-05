package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution;
import com.dalai.llama.preprod.service.continuity.ContinuityValidator;
import com.dalai.llama.preprod.service.continuity.StepContinuityService;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import com.dalai.llama.preprod.service.generation.PromptInput;
import com.dalai.llama.preprod.service.generation.PromptInputs;
import com.dalai.llama.preprod.dto.StepContinuityView;
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
    private final com.dalai.llama.preprod.repository.ScreenplaySceneCharacterRepository screenplaySceneCharacterRepository;
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
    private final String planningImageModel;
    private final String storyboardImageModel;
    private final String defaultTextModel;
    private final CreativeDirectionContextService creativeDirectionContextService;
    private final StepContinuityService stepContinuityService;

    public ShotImageService(
            ShotRepository shotRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            com.dalai.llama.preprod.repository.ScreenplaySceneCharacterRepository screenplaySceneCharacterRepository,
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
            @Value("${pre-production.llm-gateway.planning-image-model:${pre-production.llm-gateway.default-image-model}}") String planningImageModel,
            @Value("${pre-production.llm-gateway.storyboard-image-model:${pre-production.llm-gateway.default-image-model}}") String storyboardImageModel,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultTextModel,
            CreativeDirectionContextService creativeDirectionContextService,
            StepContinuityService stepContinuityService
    ) {
        this.creativeDirectionContextService = creativeDirectionContextService;
        this.stepContinuityService = stepContinuityService;
        this.shotRepository = shotRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.screenplaySceneCharacterRepository = screenplaySceneCharacterRepository;
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
        this.planningImageModel = planningImageModel;
        this.storyboardImageModel = storyboardImageModel;
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


    @Transactional(readOnly = true)
    public Map<UUID, List<ShotImageView>> listByShotIds(UUID tenantId, List<UUID> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            return Map.of();
        }

        return shotImageRepository.findByTenantIdAndShotIdIn(tenantId, shotIds).stream()
                .collect(Collectors.groupingBy(
                        ShotImage::getShotId,
                        Collectors.mapping(this::toView, Collectors.toList())
                ));
    }

    /** Same as {@link #generate(UUID, UUID, ShotImageKind, String)}, plus one or more inspiration
     * images (already-downloaded bytes, e.g. from a creator upload) to edit alongside the shot's
     * current image -- e.g. "match this reference photo's lighting". Applies to STORYBOARD,
     * LIGHTING, and CAMERA_PLAN the same way; a PRODUCTION shot with identity conditioning
     * (fal.ai reference_image_urls, a different mechanism entirely) ignores inspiration images. */
    @Transactional
    public ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind, String note, List<String> inspirationDataUris) {
        return generate(tenantId, shotId, kind, note, inspirationDataUris, null);
    }

    /** A step shot (see {@link StepShot}): this shot's {@code kind} image made by editing an
     * earlier shot's image into this shot's moment, keeping the characters who stay and the look. */
    @Transactional
    public ShotImageView generateStepFrom(UUID tenantId, UUID shotId, ShotImageKind kind, UUID sourceShotId, String note) {
        StepSource source = stepSource(tenantId, shotId, kind, sourceShotId);
        return generate(tenantId, shotId, kind, note, List.of(), source);
    }

    /** The step shot as a zip instead of a generation: the exact prompt and images
     * {@link #generateStepFrom} would send, for running the same step in an outside tool and
     * uploading the result back ("Same" replace). Makes no image, but on an identity-conditioned
     * still the prompt passes through the same reliability rewrite (one small text call). */
    @Transactional
    public ShotImageBundle stepBundle(UUID tenantId, UUID shotId, ShotImageKind kind, UUID sourceShotId, String note) {
        StepSource source = stepSource(tenantId, shotId, kind, sourceShotId);
        PreparedImageRequest request = prepare(tenantId, shotId, kind, note, List.of(), source);
        return ShotImageBundle.of(request.shot(), source.shot(), kind, request.modelId(), request.prompt(),
                request.refsForAttempt().apply(1), request.attachmentLabels(), request.params());
    }

    /** What a step would do before doing it: the resolved continuity (what is kept from the earlier
     * shot, what this shot changes, every automatic override and its reason) and the exact prompt that
     * would be sent. Makes no image; the continuity analysis is cached, so re-previewing after the
     * user changes an override costs no vision call. */
    @Transactional
    public StepContinuityView previewStep(UUID tenantId, UUID shotId, ShotImageKind kind, UUID sourceShotId, String note) {
        PreparedImageRequest request = prepare(tenantId, shotId, kind, note, List.of(), stepSource(tenantId, shotId, kind, sourceShotId));
        ContinuityResolution continuity = request.continuity();
        return new StepContinuityView(continuity.resolvedVisualState(), continuity.overrides(), request.continuityWarnings(),
                continuity.preserved(), continuity.changesForThisShot(), request.prompt());
    }

    /** Removes this shot's {@code kind} image so it can be made again from scratch (or uploaded).
     * The stored file stays in the bucket -- media assets are never hard-deleted here. */
    @Transactional
    public void deleteImage(UUID tenantId, UUID shotId, ShotImageKind kind) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        ShotImage image = shotImageRepository.findByShotIdAndKind(shotId, kind)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no " + kind + " image"));
        shotImageRepository.delete(image);
        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_DELETED", kind + " image removed");
    }

    /** The earlier shot, its image (attached first) and the primary characters of both shots. */
    private record StepSource(Shot shot, ShotImage image, String imageUri, String sourceCharacter, String targetCharacter) {}

    private StepSource stepSource(UUID tenantId, UUID shotId, ShotImageKind kind, UUID sourceShotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        Shot source = shotRepository.findByIdAndTenantId(sourceShotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + sourceShotId));
        ShotImage sourceImage = shotImageRepository.findByShotIdAndKind(sourceShotId, kind).orElse(null);
        StepShot.requireUsableSource(shot, source, sourceImage != null);
        String sourceUri = toDataUri(sourceImage);
        if (sourceUri == null) {
            throw PreProductionException.upstream("Could not read shot " + sourceShotId + "'s image to step from");
        }
        return new StepSource(source, sourceImage, sourceUri, primaryCharacterName(source), primaryCharacterName(shot));
    }

    /** {@code step}, when given, is a step shot: its earlier frame is edited into this shot instead
     * of the shot's own current image, and its instruction leads the prompt. */
    private ShotImageView generate(UUID tenantId, UUID shotId, ShotImageKind kind, String note,
                                   List<String> inspirationDataUris, StepSource step) {
        PreparedImageRequest request = prepare(tenantId, shotId, kind, note, inspirationDataUris, step);
        Shot shot = request.shot();
        CastProfile castProfile = request.castProfile();
        String prompt = request.prompt();
        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_STARTED", "Generating " + kind + " image");
        if (!request.continuityWarnings().isEmpty()) {
            generationThoughtService.log(tenantId, shotId, kind + "_STEP_CONTINUITY_WARNING", String.join(" | ", request.continuityWarnings()));
        }
        DecodedImage decoded;
        try {
            decoded = generateWithRetry(tenantId, shotId, kind, shot, request.modelId(), prompt,
                    request.params(), request.refsForAttempt());
        } catch (PreProductionException ex) {
            String explained = refusalExplanation(ex.getMessage(), request.attachmentLabels());
            if (explained == null) throw ex;
            throw PreProductionException.upstream(explained, ex);
        }
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
        // Refresh the vision-analysis cache on regenerate -- the previous run's onScreenText no
        // longer describes this image. Cleared explicitly (not just left stale) so a UI reading
        // "does this frame have visible text" gets a null instead of a lie until the analysis
        // below refills it.
        image.setDescription(null);
        image.setOnScreenText(null);
        image.setOnScreenTextLanguage(null);
        image.setUpdatedAt(now);
        image = shotImageRepository.save(image);

        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.STORYBOARD_IMAGE);
        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_GENERATED", kind + " image stored at " + objectKey);

        annotateFromVisionAnalysis(tenantId, shot.getProjectId(), image);
        return toView(image);
    }

    /** When the image model refuses every attempt (IMAGE_OTHER) with an actor's face attached, the
     * usual cause is the reference photo itself: Gemini treats every face in it as an identity to
     * keep, so a photo with a crowd, a second person or no clear face is refused however the prompt
     * is worded. Says which reference and what to use instead; null for any other failure. */
    static String refusalExplanation(String failure, List<String> attachmentLabels) {
        if (failure == null || !failure.contains("IMAGE_OTHER") || attachmentLabels == null) return null;
        List<String> faces = attachmentLabels.stream()
                .filter(label -> label.startsWith("face: "))
                .map(label -> label.substring("face: ".length()))
                .toList();
        if (faces.isEmpty()) return null;
        return "The image model declined every attempt with " + String.join(", ", faces) + "'s reference photo attached (IMAGE_OTHER). "
                + "This usually means the photo is not a clear picture of one person -- other people in the background, a busy scene "
                + "or an unclear face. Replace it with a tight head-and-shoulders photo of just that person (Cast Library -> Edit: "
                + "upload one, or use AI face), then generate again.";
    }

    /** Everything one image call sends: model, final prompt, params, and the attached images per
     * retry attempt. {@code attachmentLabels} names attempt 1's images in send order -- the step
     * bundle uses it so an outside run sees the same numbered images the prompt refers to. */
    record PreparedImageRequest(Shot shot, CastProfile castProfile, String modelId, String prompt,
                                Map<String, Object> params, java.util.function.IntFunction<List<String>> refsForAttempt,
                                List<String> attachmentLabels, ContinuityResolution continuity, List<String> continuityWarnings) {

        PreparedImageRequest withContinuityWarnings(List<String> warnings) {
            return new PreparedImageRequest(shot, castProfile, modelId, prompt, params, refsForAttempt, attachmentLabels, continuity, warnings);
        }
    }

    /** Builds the request and, for a step, checks the finished prompt against its continuity
     * resolution -- any superseded value that still reached the prompt becomes a visible warning. */
    private PreparedImageRequest prepare(UUID tenantId, UUID shotId, ShotImageKind kind, String note,
                                         List<String> inspirationDataUris, StepSource step) {
        PreparedImageRequest request = buildRequest(tenantId, shotId, kind, note, inspirationDataUris, step);
        if (step == null) {
            return request;
        }
        List<String> warnings = new java.util.ArrayList<>(request.continuity().warnings());
        warnings.addAll(ContinuityValidator.validate(request.prompt(), request.continuity()));
        return request.withContinuityWarnings(List.copyOf(warnings));
    }

    /** Builds the request {@link #generate} sends, without sending it. Shared with
     * {@link #stepBundle} so a downloaded bundle is exactly what the app would have generated from. */
    private PreparedImageRequest buildRequest(UUID tenantId, UUID shotId, ShotImageKind kind, String note,
                                              List<String> inspirationDataUris, StepSource step) {
        String editSourceUri = step == null ? null : step.imageUri();
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        CastProfile castProfile = kind == ShotImageKind.PRODUCTION ? resolveCastProfile(tenantId, shot) : null;
        ShotProductReference productReference = kind == ShotImageKind.PRODUCTION
                ? shotProductReferenceRepository.findByShotId(shotId).orElse(null) : null;
        // Other on-screen characters staged in the parent scene (antagonist alongside protagonist,
        // etc). Only meaningful for the PRODUCTION still -- storyboard/planning kinds don't
        // condition on faces and shouldn't burn extra reference images.
        List<CastProfile> secondaryCasts = kind == ShotImageKind.PRODUCTION
                ? resolveSecondarySceneCastProfiles(tenantId, shot, castProfile) : List.of();

        // Production stills follow the approved creative direction's look, and -- where no identity
        // reference is attached -- carry the client's approved reference images as style references.
        ApprovedCreativeDirectionContext creativeDirection = kind == ShotImageKind.PRODUCTION
                ? creativeDirectionContextService.forGeneration(tenantId, shot.getProjectId()).orElse(null) : null;
        LightingPlan lightingPlan = kind == ShotImageKind.PRODUCTION ? lightingPlanRepository.findByShotId(shot.getId()).orElse(null) : null;
        // A step resolves its visual state against the earlier image first; every environment value
        // in the prompt below is then read through that resolution, never raw.
        ContinuityResolution continuity = step == null ? ContinuityResolution.NONE
                : stepContinuityService.resolve(tenantId, shot, step.shot(), step.image(), step.imageUri(), kind,
                        new PromptInput.Context(shot, lightingPlan, creativeDirection));
        String prompt = promptFor(shot, kind, castProfile, productReference, secondaryCasts, creativeDirection, lightingPlan, continuity.inputs());
        if (prompt == null || prompt.isBlank()) {
            throw PreProductionException.badRequest(
                    "Shot " + shotId + " has no " + kind + " prompt available yet -- generate the shot list first");
        }
        if (note != null && !note.isBlank()) {
            prompt = prompt + "\n\nRequested change: " + note;
        }
        // A step leads with its edit instruction, so the model reads the shot description that
        // follows as the frame to mould the attached image into, not a frame to invent.
        if (step != null) {
            prompt = StepShot.instruction(step.shot(), step.sourceCharacter(), shot, step.targetCharacter(), continuity) + "\n" + prompt;
        }
        String editLabel = editSourceUri != null ? "earlier shot (step source)" : "this shot's current image";

        if (productReference != null || castProfile != null) {
            String modelId = identityImageModel;
            if (!isGeminiModel(modelId)) {
                // fal.ai FLUX_PULID's reference_image_urls param: send both refs when both exist,
                // same order as the Gemini path below so downstream behavior stays symmetric.
                List<String> refUrls = new java.util.ArrayList<>();
                List<String> labels = new java.util.ArrayList<>();
                if (castProfile != null) {
                    refUrls.add(signedUrl(castProfile.getFaceRefBucket(), castProfile.getFaceRefObjectKey()));
                    labels.add("face: " + castProfile.getDisplayName());
                }
                // Secondary scene characters in the same primary-cast -> other -> product order the
                // Gemini path uses, so ShotImagePromptBuilder's ordered subject blocks and the
                // reference_image_urls positions stay aligned across providers.
                for (CastProfile secondary : secondaryCasts) {
                    String url = signedUrl(secondary.getFaceRefBucket(), secondary.getFaceRefObjectKey());
                    if (url != null) {
                        refUrls.add(url);
                        labels.add("face: " + secondary.getDisplayName());
                    }
                }
                if (productReference != null) {
                    refUrls.add(signedUrl(productReference.getBucket(), productReference.getObjectKey()));
                    labels.add("product");
                }
                return new PreparedImageRequest(shot, castProfile, modelId, prompt,
                        Map.of("reference_image_urls", refUrls), attempt -> null, labels, continuity, List.of());
            }
            // Gemini has no separate "reference image" request param the way fal.ai's FLUX_PULID
            // does -- it conditions on whatever images ride along inline with the prompt. Each ref
            // slot is computed ONCE; generateWithRetry rebuilds the attached list per attempt via
            // identityRefsForAttempt so IMAGE_OTHER refusals get a real chance to succeed with
            // fewer refs. Order within an attempt stays current-image -> primary -> secondaries ->
            // product so the numbered subject blocks in the prompt map to images by position.
            final String currentImageUri = editSourceUri != null ? editSourceUri
                    : (note != null && !note.isBlank())
                    ? shotImageRepository.findByShotIdAndKind(shotId, kind).map(this::toDataUri).orElse(null)
                    : null;
            final String primaryUri = castProfile == null ? null
                    : toDataUri(castProfile.getFaceRefBucket(), castProfile.getFaceRefObjectKey());
            final List<CastProfile> secondariesWithFace = new java.util.ArrayList<>();
            final List<String> secondaryUris = new java.util.ArrayList<>();
            for (CastProfile secondary : secondaryCasts) {
                String uri = toDataUri(secondary.getFaceRefBucket(), secondary.getFaceRefObjectKey());
                if (uri != null) {
                    secondariesWithFace.add(secondary);
                    secondaryUris.add(uri);
                }
            }
            final String productUri = productReference == null ? null
                    : toDataUri(productReference.getBucket(), productReference.getObjectKey());
            // A step shot's whole point is the earlier frame, so it is never dropped on retry.
            final boolean keepEditSource = editSourceUri != null;
            List<String> labels = new java.util.ArrayList<>();
            if (currentImageUri != null) labels.add(editLabel);
            if (primaryUri != null) labels.add("face: " + castProfile.getDisplayName());
            secondariesWithFace.forEach(secondary -> labels.add("face: " + secondary.getDisplayName()));
            if (productUri != null) labels.add("product");
            prompt = reliabilityRewrite(tenantId, shot.getProjectId(), prompt, identityPronounHint(castProfile, productReference));
            return new PreparedImageRequest(shot, castProfile, modelId, prompt, imageParams(shot),
                    attempt -> identityRefsForAttempt(attempt, currentImageUri, keepEditSource, primaryUri, secondaryUris, productUri),
                    labels, continuity, List.of());
        }

        // Three tiers of image model routing, by kind:
        //   PRODUCTION -> default-image-model (gemini-3.1-flash-image, photoreal video anchor)
        //   STORYBOARD -> storyboard-image-model (gemini-3.1-flash-lite-image, budget sketch)
        //   LIGHTING / CAMERA_PLAN / MOTION_GRAPHIC -> planning-image-model (fal-ai/flux/schnell)
        // Config knobs are all in application.yml so a deployment can collapse the tiers
        // (e.g. set storyboard-image-model = default-image-model to disable the lite split).
        String modelId = pickModelForKind(kind);
        // A chat-requested change ("make her jacket red") should edit the actual current image, not
        // regenerate blind from text alone -- Gemini's image model accepts multiple input images
        // inline alongside the instruction and edits from them directly. Current image first (what's
        // being edited), then any inspiration image(s) (what to match/borrow from).
        List<String> images = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        String currentImageUri = editSourceUri != null ? editSourceUri
                : (note != null && !note.isBlank())
                ? shotImageRepository.findByShotIdAndKind(shotId, kind).map(this::toDataUri).orElse(null)
                : null;
        if (currentImageUri != null) {
            images.add(currentImageUri);
            labels.add(editLabel);
        }
        for (String inspiration : inspirationDataUris) {
            images.add(inspiration);
            labels.add("inspiration");
        }
        List<String> styleReferences = creativeDirectionStyleReferences(creativeDirection);
        if (!styleReferences.isEmpty()) {
            images.addAll(styleReferences);
            styleReferences.forEach(reference -> labels.add("client style reference"));
            prompt = prompt + "\n\nThe last " + styleReferences.size() + " attached image(s) are the client's approved style references: "
                    + "match their colour, light and texture only -- do not copy their subjects, layout or any text in them.";
        }
        List<String> editDataUris = images.isEmpty() ? null : images;
        return new PreparedImageRequest(shot, null, modelId, prompt, imageParams(shot, modelId),
                attempt -> editDataUris, labels, continuity, List.of());
    }

    /** Runs the vision-analysis call immediately after producing a new/replaced image, so the
     * frontend's per-image "has rendered text" affordances (Download, "fix on-image text") work
     * from the moment the tile appears -- previously these fields were populated only lazily
     * inside {@code ProjectLockService.ingestShots} on the STORYBOARD image, so a
     * PRODUCTION/MOTION_GRAPHIC image never had them at all. One extra LLM call per image; the
     * same call the lock path was already paying for, just moved earlier and per-image. Never
     * fails the caller -- the description helper degrades to {@code Description.EMPTY} on any
     * error, matching its own class-level "never breaks the shot pipeline" contract.
     *
     * <p>Gated to PRODUCTION only. The affordance this feeds (client "fix on-image text" chat)
     * only makes sense on the final photoreal frame; a black-and-white storyboard sketch, a
     * lighting build sheet, a camera-plan diagram, or a motion-graphic preview don't have
     * client-facing rendered text to correct, and paying for a describe on each of them was
     * ~65% of the per-shot describe bill for zero user-visible benefit. The shot's own text
     * context (brief, action, camera plan) still reaches chat through the existing embedding
     * pipeline -- that flow does not depend on this per-image describe. */
    private void annotateFromVisionAnalysis(UUID tenantId, UUID projectId, ShotImage image) {
        if (image.getKind() != ShotImageKind.PRODUCTION) {
            return;
        }
        ShotImageDescriptionService.Description described = shotImageDescriptionService.describe(tenantId, projectId, image);
        // Anything other than a full success (the describe helper degrades to null-fields
        // Description.EMPTY on any failure) is a "haven't successfully analyzed yet" state --
        // leave the row alone so the frontend keeps auto-firing reanalyze until it succeeds.
        if (described == null || described.description() == null || described.description().isBlank()) {
            return;
        }
        image.setDescription(described.description());
        // Store the on-image text unconditionally on a successful describe -- empty string when
        // the model saw no text -- so a NULL on_screen_text unambiguously means "never
        // successfully analyzed", not "analyzed and no text". Without this distinction, the
        // frontend would keep re-firing reanalyze every session on genuinely text-free images.
        String text = described.onScreenText() == null ? "" : described.onScreenText().trim();
        image.setOnScreenText(text);
        image.setOnScreenTextLanguage(text.isEmpty() ? null : described.onScreenTextLanguage());
        shotImageRepository.save(image);
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
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        List<String> dataUris = inspirationImages.stream()
                .map(this::toDataUri)
                .filter(java.util.Objects::nonNull)
                .toList();
        String styleDirection = dataUris.stream()
                .map(uri -> shotImageDescriptionService.analyzeInspiration(tenantId, shot.getProjectId(), uri))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("\n"));
        String combinedNote = styleDirection.isBlank()
                ? note
                : (note == null || note.isBlank() ? "" : note + "\n\n") + styleDirection;
        return generate(tenantId, shotId, kind, combinedNote, dataUris);
    }

    private String toDataUri(MultipartFile file) {
        try {
            return ModelImage.dataUri(file.getBytes());
        } catch (Exception ex) {
            return null;
        }
    }

    /** "Same" upload flow: the creator has downloaded a shot image, hand-corrected the wrong text
     * (Gemini's text rendering is famously unreliable), and is uploading the fixed version as the
     * authoritative image for this shot+kind. No LLM call, no re-analysis -- store the exact
     * bytes and update the existing shot_image row (or create one if none existed yet, e.g. a
     * shot whose auto-generated image failed). Sibling of {@link #generateWithInspiration}
     * (which does re-generate); the caller (frontend) chooses between them via a "same/inspired"
     * toggle. */
    @Transactional
    public ShotImageView replaceImage(UUID tenantId, UUID shotId, ShotImageKind kind, MultipartFile file) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (file == null || file.isEmpty()) {
            throw PreProductionException.badRequest("Uploaded file is empty");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception ex) {
            throw PreProductionException.badRequest("Could not read uploaded file: " + ex.getMessage());
        }
        String contentType = file.getContentType() != null ? file.getContentType() : "image/png";
        String extension = extensionFor(contentType, file.getOriginalFilename());

        String objectKey = "%s/%s/%s/%s.%s".formatted(storyboardPrefix, kind.name().toLowerCase(), shotId, UUID.randomUUID(), extension);
        upload(objectKey, new DecodedImage(bytes, contentType, extension));

        OffsetDateTime now = OffsetDateTime.now();
        ShotImage image = shotImageRepository.findByShotIdAndKind(shotId, kind).orElseGet(() -> ShotImage.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .kind(kind)
                .createdAt(now)
                .build());
        image.setBucket(bucket);
        image.setObjectKey(objectKey);
        // Deliberately clear prompt/referenceCastProfileId: this image was NOT generated from
        // them, so leaving the previous run's values here would misrepresent provenance. Also
        // clear the cached vision-analysis fields -- the previous image's onScreenText no longer
        // describes what the user just uploaded; the annotate call below refills them per the new
        // bytes, so the frontend's "has rendered text" affordances stay accurate.
        image.setPrompt(null);
        image.setReferenceCastProfileId(null);
        image.setDescription(null);
        image.setOnScreenText(null);
        image.setOnScreenTextLanguage(null);
        image.setUpdatedAt(now);
        image = shotImageRepository.save(image);

        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.STORYBOARD_IMAGE);
        generationThoughtService.log(tenantId, shotId, kind + "_IMAGE_UPLOADED",
                kind + " image replaced by manual upload, stored at " + objectKey);

        annotateFromVisionAnalysis(tenantId, shot.getProjectId(), image);
        return toView(image);
    }

    /** Prefer the declared Content-Type; fall back to the filename extension, then to png. */
    private String extensionFor(String contentType, String originalFilename) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("png")) return "png";
            if (lower.contains("webp")) return "webp";
            if (lower.contains("jpeg") || lower.contains("jpg")) return "jpg";
        }
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("webp")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return "png";
    }

    /** Backfill entry point for shot images generated before the vision-analysis-on-generate change
     * shipped (V56 + ShotImageService.annotateFromVisionAnalysis). Those rows have
     * on_screen_text / on_screen_text_language NULL forever otherwise, so the per-image Download
     * affordance can never surface for text-bearing frames. Same describe() call the generate/
     * replace paths already fire, just applied to the already-stored bytes. Safe to call any time --
     * it just refreshes the cached fields, never touches the image bytes. */
    @Transactional
    public ShotImageView reanalyzeVisualDescription(UUID tenantId, UUID shotId, ShotImageKind kind) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        ShotImage image = shotImageRepository.findByShotIdAndKind(shotId, kind)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no " + kind + " image yet"));
        annotateFromVisionAnalysis(tenantId, shot.getProjectId(), image);
        return toView(image);
    }

    /** Project-wide legacy backfill: finds every shot_image in the project whose on_screen_text is
     * NULL (i.e. was never successfully analyzed under the V56/V83 vision-with-language contract)
     * and runs the same describe() call on each. Fired automatically once per project per session
     * from the frontend so a creator visiting a project locked before the on_screen_text feature
     * shipped doesn't have to open every tile individually to unlock its Download button.
     *
     * <p>Returns the number of images actually re-analyzed. Images with on_screen_text already set
     * (including the sentinel empty string set for "analyzed but has no text") are skipped for
     * free -- no LLM call, no wasted cost. */
    @Transactional
    public int reanalyzeMissingForProject(UUID tenantId, UUID projectId) {
        List<Shot> projectShots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .filter(s -> tenantId.equals(s.getTenantId()))
                .toList();
        if (projectShots.isEmpty()) return 0;
        List<UUID> shotIds = projectShots.stream().map(Shot::getId).toList();
        List<ShotImage> images = shotImageRepository.findByShotIdIn(shotIds).stream()
                .filter(i -> i.getOnScreenText() == null)
                .toList();
        int fired = 0;
        for (ShotImage image : images) {
            annotateFromVisionAnalysis(tenantId, projectId, image);
            fired++;
        }
        return fired;
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

    /** The approved direction's client reference images, as data URIs for the image model. Only
     * on the non-identity path: identity-conditioned stills number their references by position,
     * and an extra image there would shift every subject block. */
    private List<String> creativeDirectionStyleReferences(ApprovedCreativeDirectionContext creativeDirection) {
        if (creativeDirection == null) {
            return List.of();
        }
        return creativeDirection.imageReferences().stream()
                .map(reference -> toDataUri(reference.bucket(), reference.objectKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String promptFor(Shot shot, ShotImageKind kind, CastProfile castProfile,
                             ShotProductReference productReference, List<CastProfile> secondaryCasts,
                             ApprovedCreativeDirectionContext creativeDirection, LightingPlan lightingPlan, PromptInputs inputs) {
        return switch (kind) {
            case STORYBOARD -> wrapAsStoryboardSketch(shot.getSketchPrompt());
            // PRODUCTION reads the LightingPlan too (when one exists) so key/fill/rim direction
            // reaches the still. Selective -- ShotImagePromptBuilder skips numbered build steps
            // and gear part numbers, which belong on the lighting sheet, not the finished frame.
            case PRODUCTION -> ShotImagePromptBuilder.buildProductionPrompt(
                    shot, castProfile, productReference, lightingPlan, secondaryCasts, creativeDirection, inputs);
            case LIGHTING -> ShotImagePromptBuilder.buildLightingSheetPrompt(shot, lightingPlanRepository.findByShotId(shot.getId()).orElse(null));
            case CAMERA_PLAN -> ShotImagePromptBuilder.buildCameraPlanSheetPrompt(shot, cameraPlanRepository.findByShotId(shot.getId()).orElse(null));
            case MOTION_GRAPHIC -> ShotImagePromptBuilder.buildMotionGraphicPreviewPrompt(shot, motionGraphicPlanRepository.findByShotId(shot.getId()).orElse(null));
        };
    }

    /** Shot.sketchPrompt as the shot-list generator writes it is a plain scene description
     * ("medium close-up of Anjali, ..., soft indoor light") -- no style hint at all. Feeding
     * that raw to gemini-3.1-flash-lite-image produced a photoreal frame instead of the
     * intended storyboard sketch (Pragya reported this on shot 1: expected black-and-white
     * sketch, got a lite-quality photoreal frame). Wrapping the scene text in an explicit
     * storyboard-style header + framing instructions here keeps every existing shot's
     * sketchPrompt reusable without a schema/backfill change, and every future STORYBOARD
     * request lands with the right art direction.
     *
     * <p>Deliberately does NOT ask for a "hero product inset" -- the earlier wording did, but
     * STORYBOARD calls attach zero product reference images (see the {@code kind == PRODUCTION}
     * gates on castProfile/productReference in {@link #generate(UUID, UUID, ShotImageKind,
     * String, List)}). Gemini had no source-of-truth for the actual product shape and invented
     * a generic one, which is worse than a blank corner. */
    static String wrapAsStoryboardSketch(String scene) {
        if (scene == null || scene.isBlank()) return scene;
        return "STORYBOARD PANEL -- pencil-and-ink black-and-white storyboard sketch, hand-drawn look, "
                + "loose but confident lines, cross-hatched shading, no colour, no photorealism, "
                + "landscape panel with a thin outer border. Add brief on-panel notes for camera angle "
                + "and framing (compact, hand-lettered). Under no circumstance render this as a photo, "
                + "photorealistic frame, or color rendering -- if in doubt, prefer looser hand-drawn lines.\n\n"
                + "Scene: " + scene.trim();
    }

    /** Every OTHER on-screen character staged in the shot's parent scene (via
     * screenplay_scene_character), minus the shot's primary character and any PRODUCT/NARRATOR
     * roles (product rides on ShotProductReference; narrator has no face). Their face refs are
     * attached to the PRODUCTION still so a scene that stages protagonist AND antagonist doesn't
     * ship an image with only the primary's identity locked and the antagonist invented from
     * scratch. Empty list when the scene has no other characters, no cast assignment for them,
     * or no scene at all. */
    private List<CastProfile> resolveSecondarySceneCastProfiles(UUID tenantId, Shot shot, CastProfile primary) {
        if (shot.getScreenplaySceneId() == null) return List.of();
        List<UUID> sceneCharacterIds = screenplaySceneCharacterRepository.findByScreenplaySceneId(shot.getScreenplaySceneId()).stream()
                .map(link -> link.getScriptCharacterId())
                .toList();
        if (sceneCharacterIds.isEmpty()) return List.of();
        Map<UUID, ScriptCharacter> charactersById = scriptCharacterRepository.findAllById(sceneCharacterIds).stream()
                .collect(Collectors.toMap(ScriptCharacter::getId, c -> c, (a, b) -> a));
        List<CastAssignment> assignments = castAssignmentRepository.findByProjectId(shot.getProjectId()).stream()
                .filter(a -> charactersById.containsKey(a.getScriptCharacterId()))
                .toList();
        if (assignments.isEmpty()) return List.of();
        List<UUID> profileIds = assignments.stream().map(CastAssignment::getCastProfileId).distinct().toList();
        Map<UUID, CastProfile> profileById = castProfileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(CastProfile::getId, p -> p));
        java.util.UUID primaryId = primary == null ? null : primary.getId();
        List<CastProfile> result = new java.util.ArrayList<>();
        for (CastAssignment a : assignments) {
            ScriptCharacter sc = charactersById.get(a.getScriptCharacterId());
            if (sc == null) continue;
            if (sc.getCharacterType() == com.dalai.llama.preprod.domain.CharacterType.PRODUCT
                    || sc.getCharacterType() == com.dalai.llama.preprod.domain.CharacterType.NARRATOR) continue;
            CastProfile p = profileById.get(a.getCastProfileId());
            if (p == null) continue;
            if (primaryId != null && primaryId.equals(p.getId())) continue;
            if (p.getFaceRefBucket() == null || p.getFaceRefObjectKey() == null) continue;
            result.add(p);
        }
        return result;
    }

    /** The display name of the shot's primary character, or null when it has none. */
    private String primaryCharacterName(Shot shot) {
        if (shot.getPrimaryCharacterKey() == null || shot.getPrimaryCharacterKey().isBlank()) {
            return null;
        }
        return scriptRepository.findByProjectId(shot.getProjectId())
                .flatMap(script -> scriptCharacterRepository.findByScriptIdAndCharacterKey(script.getId(), shot.getPrimaryCharacterKey()))
                .map(ScriptCharacter::getCharacterName)
                .filter(name -> !name.isBlank())
                .orElse(shot.getPrimaryCharacterKey());
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
        return generateWithRetry(tenantId, shotId, kind, shot, modelId, prompt, params,
                attempt -> editDataUris);
    }

    /** Attempt-aware variant: {@code refsForAttempt} rebuilds the identity-reference set per try
     * so the identity path can PROGRESSIVELY STRIP references on retry -- Gemini's
     * IMAGE_OTHER refusals are correlated with too many reference photos in one call (esp.
     * multiple face refs alongside a product ref and a current-image edit source). Sending the
     * same maxed-out payload 3 times just yielded the same 3 refusals. The identity caller uses
     * this to drop secondaries on attempt 2 and drop the current-image edit ref on attempt 3,
     * so at least the primary-character-only call gets a chance to land before we give up. */
    private DecodedImage generateWithRetry(UUID tenantId, UUID shotId, ShotImageKind kind, Shot shot,
            String modelId, String prompt, Map<String, Object> params,
            java.util.function.IntFunction<List<String>> refsForAttempt) {
        PreProductionException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<String> attemptRefs = refsForAttempt.apply(attempt);
                LlmGatewayChatResponse response = llmGatewayClient.chat(
                        tenantId.toString(),
                        "shot-image-" + kind.name().toLowerCase() + "-" + shotId + "-" + UUID.randomUUID(),
                        new LlmGatewayChatRequest(modelId, List.of(new LlmGatewayMessage("user", prompt, attemptRefs)), params, null, null)
                                .withProjectId(shot.getProjectId()));
                DecodedImage decoded = decode(response, kind);
                if (!isPlanningKind(kind)) {
                    // Planning kinds (LIGHTING/CAMERA_PLAN/MOTION_GRAPHIC) come off fal.ai FLUX
                    // schnell which honors image_size approximately but not exactly -- schematic
                    // diagrams don't need strict aspect enforcement, and rejecting them here would
                    // burn all 3 retries on a shape mismatch that doesn't matter for the artifact.
                    validateAspectRatio(decoded, shot, kind);
                }
                return decoded;
            } catch (PreProductionException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure;
    }

    /** Trim policy for the Gemini identity path across the 3-retry budget:
     * <ul>
     *   <li>attempt 1: everything (current image edit source + primary + secondaries + product)</li>
     *   <li>attempt 2: drop secondaries -- keep primary + product + current image edit source</li>
     *   <li>attempt 3: drop the current image edit source too -- primary + product only, the
     *       most permissive combination Gemini reliably accepts. Not for a step shot
     *       ({@code keepEditSource}): its prompt says "the first attached image is shot N", and
     *       without that frame the result silently loses all continuity.</li>
     * </ul>
     * A null slot is skipped naturally. */
    static List<String> identityRefsForAttempt(int attempt, String currentImageUri, boolean keepEditSource,
                                               String primaryUri, List<String> secondaryUris, String productUri) {
        List<String> out = new java.util.ArrayList<>();
        if ((attempt <= 2 || keepEditSource) && currentImageUri != null) out.add(currentImageUri);
        if (primaryUri != null) out.add(primaryUri);
        if (attempt == 1 && secondaryUris != null) {
            for (String uri : secondaryUris) if (uri != null) out.add(uri);
        }
        if (productUri != null) out.add(productUri);
        return out.isEmpty() ? null : out;
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
            // Sized and typed for the model: several of these go out in one request.
            return ModelImage.dataUri(buffer.toByteArray());
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
        return imageParams(shot, imageModel);
    }

    /** Same idea, provider-aware: Gemini gets its {@code aspect_ratio} + {@code
     * response_format=image} shape; fal.ai FLUX schnell doesn't understand either and instead
     * takes {@code image_size} as an enum -- LIGHTING/CAMERA_PLAN/MOTION_GRAPHIC being
     * schematic-style diagrams, a slight aspect drift is fine (and generateWithRetry's aspect
     * validation is gated by isPlanningKind so it doesn't reject them for it). */
    private Map<String, Object> imageParams(Shot shot, String modelId) {
        if (isGeminiModel(modelId)) {
            String ratio = geminiAspectRatio(shot.getAspectRatio());
            return ratio == null
                    ? Map.of("response_format", "image")
                    : Map.of("response_format", "image", "aspect_ratio", ratio);
        }
        // fal.ai flux/schnell -- image_size hint by shot aspect. Values match fal.ai's real
        // documented enum for flux/schnell (square_hd default, portrait_16_9 / landscape_16_9 / etc).
        String imageSize = fluxImageSize(shot.getAspectRatio());
        return imageSize == null ? Map.of() : Map.of("image_size", imageSize);
    }

    /** True for kinds routed to planning-image-model -- schematic-style diagrams whose product
     * fidelity does not matter and whose aspect ratio is not strictly enforced. */
    private static boolean isPlanningKind(ShotImageKind kind) {
        return kind == ShotImageKind.LIGHTING
                || kind == ShotImageKind.CAMERA_PLAN
                || kind == ShotImageKind.MOTION_GRAPHIC;
    }

    private String pickModelForKind(ShotImageKind kind) {
        if (isPlanningKind(kind)) {
            return planningImageModel;
        }
        if (kind == ShotImageKind.STORYBOARD) {
            return storyboardImageModel;
        }
        return imageModel;
    }

    private String fluxImageSize(com.dalai.llama.preprod.domain.AspectRatio aspectRatio) {
        if (aspectRatio == null) {
            return null;
        }
        return switch (aspectRatio) {
            case RATIO_16_9, RATIO_21_9 -> "landscape_16_9";
            case RATIO_9_16 -> "portrait_16_9";
            case RATIO_4_5 -> "portrait_4_3";
            case RATIO_1_1 -> "square_hd";
        };
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
                signedUrl(image.getBucket(), image.getObjectKey()),
                image.getOnScreenText(), image.getOnScreenTextLanguage(), image.getCreatedAt());
    }

    private record DecodedImage(byte[] bytes, String contentType, String extension) {
    }
}
