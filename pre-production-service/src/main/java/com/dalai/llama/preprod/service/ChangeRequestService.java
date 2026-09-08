package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ChangeRequestStatus;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ChangeRequest;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.SuggestionTargetType;
import com.dalai.llama.preprod.dto.ChangeRequestView;
import com.dalai.llama.preprod.dto.SuggestChangeRequestRequest;
import com.dalai.llama.preprod.repository.ChangeRequestRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A client's chat-suggested change (see chat-service's {@code SuggestPreProductionChangeAction
 * Executor}) is only ever logged here, never auto-applied -- {@link #apply} is what the creator
 * calls from their own authenticated project page, and only then does anything actually
 * regenerate. {@code targetType} is validated against {@link
 * com.dalai.llama.preprod.domain.entity.SuggestionTargetType}'s master table at creation time, and
 * {@link #apply} dispatches on that same value to whichever service already owns regenerating
 * that piece -- no new generation logic here, just routing.
 */
@Service
public class ChangeRequestService {

    private final ChangeRequestRepository changeRequestRepository;
    private final SuggestionTargetTypeService suggestionTargetTypeService;
    private final ProjectService projectService;
    private final ShotRepository shotRepository;
    private final ShotImageRepository shotImageRepository;
    private final ScriptGenerationService scriptGenerationService;
    private final ScreenplayGenerationService screenplayGenerationService;
    private final ShotImageService shotImageService;
    private final ShotImageEditPromptComposer shotImageEditPromptComposer;

    public ChangeRequestService(
            ChangeRequestRepository changeRequestRepository,
            SuggestionTargetTypeService suggestionTargetTypeService,
            ProjectService projectService,
            ShotRepository shotRepository,
            ShotImageRepository shotImageRepository,
            ScriptGenerationService scriptGenerationService,
            ScreenplayGenerationService screenplayGenerationService,
            ShotImageService shotImageService,
            ShotImageEditPromptComposer shotImageEditPromptComposer
    ) {
        this.changeRequestRepository = changeRequestRepository;
        this.suggestionTargetTypeService = suggestionTargetTypeService;
        this.projectService = projectService;
        this.shotRepository = shotRepository;
        this.shotImageRepository = shotImageRepository;
        this.scriptGenerationService = scriptGenerationService;
        this.screenplayGenerationService = screenplayGenerationService;
        this.shotImageService = shotImageService;
        this.shotImageEditPromptComposer = shotImageEditPromptComposer;
    }

    /** Called by chat-service, service-to-service, immediately after the model proposes the
     * SUGGEST_PRE_PRODUCTION_CHANGE action -- tenantId/projectId are both trusted (they came from
     * the internal path, not model output), targetType/targetRef/note are the model's free-text
     * extraction and get validated here. */
    @Transactional
    public ChangeRequestView create(UUID tenantId, UUID projectId, SuggestChangeRequestRequest request) {
        projectService.requireProject(tenantId, projectId);
        SuggestionTargetType targetType = suggestionTargetTypeService.requireActive(request.targetType());
        if (Boolean.TRUE.equals(targetType.getRequiresTargetRef())
                && (request.targetRef() == null || request.targetRef().isBlank())) {
            throw PreProductionException.badRequest(targetType.getCode() + " requires a targetRef");
        }

        OffsetDateTime now = OffsetDateTime.now();
        String storedNote = composeNoteIfShotImage(tenantId, projectId, targetType.getCode(), request.targetRef(), request.note());
        ChangeRequest changeRequest = changeRequestRepository.save(ChangeRequest.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .targetType(targetType.getCode())
                .targetRef(request.targetRef())
                .note(storedNote)
                .status(ChangeRequestStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toView(changeRequest);
    }

    /** For SHOT_IMAGE suggestions only: rewrite the raw creator note into an edit-safe instruction
     * that preserves the rest of the frame (see {@link ShotImageEditPromptComposer}) -- otherwise
     * the note flows through unchanged, same as before. Any failure to resolve the target image
     * or compose the prompt falls back to the raw note, so a broken composition never blocks a
     * chat suggestion from being logged. */
    private String composeNoteIfShotImage(UUID tenantId, UUID projectId, String targetTypeCode, String targetRef, String rawNote) {
        if (!"SHOT_IMAGE".equals(targetTypeCode) || rawNote == null || rawNote.isBlank() || targetRef == null) {
            return rawNote;
        }
        String[] parts = targetRef.split(":", 2);
        if (parts.length != 2) {
            return rawNote;
        }
        ShotImageKind kind;
        try {
            kind = ShotImageKind.valueOf(parts[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            return rawNote;
        }
        String currentOnScreenText = shotRepository.findByProjectIdAndShotRef(projectId, parts[0])
                .filter(s -> s.getTenantId().equals(tenantId))
                .flatMap(s -> shotImageRepository.findByShotIdAndKind(s.getId(), kind))
                .map(image -> image.getOnScreenText())
                .orElse(null);
        return shotImageEditPromptComposer.compose(tenantId, rawNote, currentOnScreenText);
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestView> list(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        return changeRequestRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** Dispatches to whichever service already owns regenerating the target -- SCRIPT/SCREENPLAY
     * reuse their existing full-regenerate flow with the note folded into the input text;
     * SHOT_IMAGE regenerates exactly the one referenced shot/kind, nothing else. */
    @Transactional
    public ChangeRequestView apply(UUID tenantId, UUID projectId, UUID changeRequestId) {
        return apply(tenantId, projectId, changeRequestId, List.of());
    }

    /** Same as {@link #apply(UUID, UUID, UUID)}, plus optional inspiration image(s) attached at
     * apply time (SHOT_IMAGE only -- e.g. "match this reference photo") -- see {@link
     * ShotImageService#generateWithInspiration}. Ignored for every other target type. */
    @Transactional
    public ChangeRequestView apply(UUID tenantId, UUID projectId, UUID changeRequestId, List<MultipartFile> inspirationImages) {
        ChangeRequest changeRequest = requireOwnRequest(tenantId, projectId, changeRequestId);
        switch (changeRequest.getTargetType()) {
            case "SCRIPT" -> scriptGenerationService.regenerateWithNote(tenantId, projectId, changeRequest.getNote());
            case "SCREENPLAY" -> screenplayGenerationService.regenerateWithNote(tenantId, projectId, changeRequest.getNote());
            case "SHOT_IMAGE" -> applyShotImageChange(tenantId, projectId, changeRequest, inspirationImages);
            default -> throw PreProductionException.badRequest("No apply handler for target type " + changeRequest.getTargetType());
        }
        changeRequest.setStatus(ChangeRequestStatus.APPLIED);
        changeRequest.setUpdatedAt(OffsetDateTime.now());
        return toView(changeRequestRepository.save(changeRequest));
    }

    @Transactional
    public ChangeRequestView dismiss(UUID tenantId, UUID projectId, UUID changeRequestId) {
        ChangeRequest changeRequest = requireOwnRequest(tenantId, projectId, changeRequestId);
        changeRequest.setStatus(ChangeRequestStatus.DISMISSED);
        changeRequest.setUpdatedAt(OffsetDateTime.now());
        return toView(changeRequestRepository.save(changeRequest));
    }

    private void applyShotImageChange(UUID tenantId, UUID projectId, ChangeRequest changeRequest,
            List<MultipartFile> inspirationImages) {
        String[] parts = changeRequest.getTargetRef() == null ? new String[0] : changeRequest.getTargetRef().split(":", 2);
        if (parts.length != 2) {
            throw PreProductionException.badRequest("targetRef must be \"<shotRef>:<imageKind>\", got \"" + changeRequest.getTargetRef() + "\"");
        }
        Shot shot = shotRepository.findByProjectIdAndShotRef(projectId, parts[0])
                .filter(s -> s.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No shot " + parts[0] + " in project " + projectId));
        ShotImageKind kind;
        try {
            kind = ShotImageKind.valueOf(parts[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw PreProductionException.badRequest("Unknown image kind \"" + parts[1] + "\"");
        }
        if (inspirationImages.isEmpty()) {
            shotImageService.generate(tenantId, shot.getId(), kind, changeRequest.getNote());
        } else {
            shotImageService.generateWithInspiration(tenantId, shot.getId(), kind, changeRequest.getNote(), inspirationImages);
        }
    }

    private ChangeRequest requireOwnRequest(UUID tenantId, UUID projectId, UUID changeRequestId) {
        ChangeRequest changeRequest = changeRequestRepository.findByIdAndTenantId(changeRequestId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No change request " + changeRequestId));
        if (!changeRequest.getProjectId().equals(projectId)) {
            throw PreProductionException.notFound("No change request " + changeRequestId + " for project " + projectId);
        }
        return changeRequest;
    }

    private ChangeRequestView toView(ChangeRequest changeRequest) {
        return new ChangeRequestView(changeRequest.getId(), changeRequest.getTargetType(), changeRequest.getTargetRef(),
                changeRequest.getNote(), changeRequest.getStatus(), changeRequest.getCreatedAt());
    }
}
