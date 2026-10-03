package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView;
import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView.ReferenceAsset;
import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView.ReferenceMediaType;
import com.dalai.llama.creativeplanning.dto.ReferenceImageAnalysisView;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves a pre-production project back to its creative inputs -- Project.id == LockedIdea.projectId
 * -> LockedIdea (+ its ProjectRequirement, when it came from a brief) -- and lists the client's
 * reference images and videos through the existing reference services, so the signed URLs and
 * stored analysis are the same ones the brief pages show. Nothing is copied or re-uploaded.
 */
@Service
public class ProjectCreativeContextService {

    private final LockedIdeaRepository lockedIdeaRepository;
    private final ProjectRequirementRepository projectRequirementRepository;
    private final ProjectReferenceImageService projectReferenceImageService;
    private final ProjectReferenceVideoService projectReferenceVideoService;

    public ProjectCreativeContextService(LockedIdeaRepository lockedIdeaRepository,
                                         ProjectRequirementRepository projectRequirementRepository,
                                         ProjectReferenceImageService projectReferenceImageService,
                                         ProjectReferenceVideoService projectReferenceVideoService) {
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.projectRequirementRepository = projectRequirementRepository;
        this.projectReferenceImageService = projectReferenceImageService;
        this.projectReferenceVideoService = projectReferenceVideoService;
    }

    @Transactional(readOnly = true)
    public ProjectCreativeContextView forProject(UUID tenantId, UUID projectId) {
        LockedIdea idea = lockedIdeaRepository.findTopByProjectIdOrderByCreatedAtDesc(projectId)
                .filter(found -> found.getTenantId().equals(tenantId))
                .orElseThrow(() -> CreativePlanningException.notFound("No locked idea for project " + projectId));
        ProjectRequirement requirement = idea.getProjectRequirementId() == null ? null
                : projectRequirementRepository.findById(idea.getProjectRequirementId()).orElse(null);

        return new ProjectCreativeContextView(
                idea.getId(),
                new ProjectCreativeContextView.Idea(idea.getTitle(), idea.getConcept(), idea.getTargetAudience(),
                        idea.getCampaignAngle(), idea.getKeyMessage(), idea.getTone()),
                requirement == null ? null : new ProjectCreativeContextView.Brief(requirement.getId(), requirement.getBriefText(),
                        requirement.getTargetAudience(), requirement.getCampaignDirection(), requirement.getDurationSeconds()),
                requirement == null ? List.of() : referencesFor(requirement));
    }

    private List<ReferenceAsset> referencesFor(ProjectRequirement requirement) {
        Stream<ReferenceAsset> images = projectReferenceImageService.list(requirement.getId()).stream()
                .map(image -> new ReferenceAsset(image.id(), ReferenceMediaType.IMAGE, image.bucket(), image.objectKey(),
                        image.signedUrl(), null, null, null, describe(image.analysis())));
        // A brief records one instruction for its reference clips -- what shots from them should
        // convey -- so it belongs to every clip, not to any single one.
        String clipInstruction = Boolean.TRUE.equals(requirement.getIncludeVideoShots()) ? requirement.getVideoShotsIntent() : null;
        Stream<ReferenceAsset> videos = projectReferenceVideoService.list(requirement.getId()).stream()
                .map(video -> new ReferenceAsset(video.id(), ReferenceMediaType.VIDEO, video.bucket(), video.objectKey(),
                        video.signedUrl(), video.contentType(), video.originalFilename(), clipInstruction, null));
        return Stream.concat(images, videos).toList();
    }

    private static String describe(ReferenceImageAnalysisView analysis) {
        if (analysis == null) {
            return null;
        }
        String text = Stream.of(
                        label("Description", analysis.description()),
                        label("Dominant colours", analysis.dominantColors()),
                        label("Style", analysis.styleNotes()),
                        label("Subject", analysis.subjectMatter()),
                        label("Suggested use", analysis.suggestedUseCase()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        return text.isBlank() ? null : text;
    }

    private static String label(String name, String value) {
        return value == null || value.isBlank() ? null : name + ": " + value;
    }
}
