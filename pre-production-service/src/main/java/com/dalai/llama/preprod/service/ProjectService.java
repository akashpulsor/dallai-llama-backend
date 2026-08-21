package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.dto.CreateProjectRequest;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.lifecycle.ProjectStateMachine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectConfigRepository projectConfigRepository;
    private final ProjectStateMachine stateMachine;

    public ProjectService(ProjectRepository projectRepository, ProjectConfigRepository projectConfigRepository, ProjectStateMachine stateMachine) {
        this.projectRepository = projectRepository;
        this.projectConfigRepository = projectConfigRepository;
        this.stateMachine = stateMachine;
    }

    /** The one entry point into this service -- always from a locked idea (doc §9.6). A
     * PROJECT_CONFIG row is created alongside with no preferences set yet; callers populate it
     * once budget_tier-driven defaults are wired up (a named follow-up, not part of this slice). */
    @Transactional
    public ProjectView createFromLockedIdea(UUID tenantId, CreateProjectRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        Project project = Project.builder()
                .tenantId(tenantId)
                .name(request.name())
                .lockedIdeaId(request.lockedIdeaId())
                .budgetTier(request.budgetTier())
                .status(ProjectStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build();
        project = projectRepository.save(project);

        projectConfigRepository.save(ProjectConfig.builder()
                .projectId(project.getId())
                .createdAt(now)
                .updatedAt(now)
                .build());

        return toView(project);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID tenantId, UUID projectId) {
        return toView(requireProject(tenantId, projectId));
    }

    /** Backs the project picker -- lets the UI list every project for the tenant and reopen any
     * one of them; each project's {@code status} tells the caller which stage (script/screenplay/
     * shot-list) to resume into, and the existing per-stage GET endpoints (script/screenplay/
     * shots) let it re-fetch whichever earlier state the user navigates back to. */
    @Transactional(readOnly = true)
    public List<ProjectView> list(UUID tenantId) {
        return projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** The only place {@code Project.status} is ever written -- every generation service calls
     * this instead of mutating the entity directly, so {@link ProjectStateMachine} is the one
     * place that can reject an illegal stage jump. */
    @Transactional
    public void advanceStatus(UUID tenantId, UUID projectId, ProjectStatus target) {
        Project project = requireProject(tenantId, projectId);
        project.setStatus(stateMachine.transition(project.getStatus(), target));
        project.setUpdatedAt(OffsetDateTime.now());
        projectRepository.save(project);
    }

    Project requireProject(UUID tenantId, UUID projectId) {
        return projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
    }

    private ProjectView toView(Project project) {
        return new ProjectView(
                project.getId(), project.getName(), project.getLockedIdeaId(),
                project.getBudgetTier(), project.getStatus(), project.getCreatedAt());
    }
}
