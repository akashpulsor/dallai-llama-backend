package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.repository.CreativeDirectionGenerationRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionReferenceRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The single place a downstream generation stage gets the project's creative contract from. Every
 * stage that makes a narrative, stylistic or visual decision -- hook/beat, script, screenplay, shot
 * list, camera, lighting and motion-graphic plans, production frames -- asks here, so the approved
 * direction reaches all of them the same way and none of them reads the creative-direction tables.
 * <p>
 * A project created with Creative Direction cannot generate dependent stages without an approved
 * direction ({@link #forGeneration} refuses). A project created before it existed keeps its legacy
 * path: no direction, and the templates receive {@link ApprovedCreativeDirectionContext#NONE_APPROVED}.
 */
@Service
public class CreativeDirectionContextService {

    private final ProjectRepository projectRepository;
    private final CreativeDirectionRepository creativeDirectionRepository;
    private final CreativeDirectionGenerationRepository generationRepository;
    private final CreativeDirectionReferenceRepository referenceRepository;
    private final CreativeDirectionMapper mapper;

    public CreativeDirectionContextService(ProjectRepository projectRepository,
                                           CreativeDirectionRepository creativeDirectionRepository,
                                           CreativeDirectionGenerationRepository generationRepository,
                                           CreativeDirectionReferenceRepository referenceRepository,
                                           CreativeDirectionMapper mapper) {
        this.projectRepository = projectRepository;
        this.creativeDirectionRepository = creativeDirectionRepository;
        this.generationRepository = generationRepository;
        this.referenceRepository = referenceRepository;
        this.mapper = mapper;
    }

    /** The approved direction, empty when none is approved. */
    @Transactional(readOnly = true)
    public Optional<ApprovedCreativeDirectionContext> findApproved(UUID projectId) {
        return creativeDirectionRepository.findByProjectIdAndReviewStatus(projectId, CreativeDirectionReviewStatus.APPROVED)
                .map(direction -> mapper.toContext(direction,
                        generationRepository.findById(direction.getGenerationId()).orElse(null),
                        referenceRepository.findByCreativeDirectionId(direction.getId())));
    }

    /** For a generation stage: the approved direction, or empty on a legacy project without one.
     * A project that requires Creative Direction and has none approved is refused here -- no
     * dependent stage may silently fall back to the idea alone or to the AI's recommendation. */
    @Transactional(readOnly = true)
    public Optional<ApprovedCreativeDirectionContext> forGeneration(UUID tenantId, UUID projectId) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Optional<ApprovedCreativeDirectionContext> approved = findApproved(projectId);
        if (approved.isEmpty() && project.isCreativeDirectionRequired()) {
            throw PreProductionException.conflict(
                    "Approve a creative direction for this project before generating its script, screenplay, shots or frames");
        }
        return approved;
    }

    /** What a text template receives for {{creativeDirection}}. */
    @Transactional(readOnly = true)
    public String promptBlock(UUID tenantId, UUID projectId) {
        return forGeneration(tenantId, projectId)
                .map(ApprovedCreativeDirectionContext::promptBlock)
                .orElse(ApprovedCreativeDirectionContext.NONE_APPROVED);
    }
}
