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
     * once budget_tier-driven defaults are wired up (a named follow-up, not part of this slice).
     * <p>
     * Idempotent by {@code lockedIdeaId}: creative-planning-service's own idempotency guard
     * around this call (see {@code ProjectRequirementIdeaService.lockOption}) is defense in
     * depth, not the only line of defense -- a caller retry (timeout after this side already
     * committed, a double-click, etc.) with the same lockedIdeaId must return the existing
     * project rather than mint a second one, since a lockedIdeaId is never reused across a real
     * second project. */
    @Transactional
    public ProjectView createFromLockedIdea(UUID tenantId, CreateProjectRequest request) {
        var existing = projectRepository.findByLockedIdeaIdAndTenantId(request.lockedIdeaId(), tenantId);
        if (existing.isPresent()) {
            return toView(existing.get());
        }

        OffsetDateTime now = OffsetDateTime.now();
        Project project = Project.builder()
                .tenantId(tenantId)
                .name(request.name())
                .lockedIdeaId(request.lockedIdeaId())
                .budgetTier(request.budgetTier())
                .status(ProjectStatus.DRAFT)
                .reviewAllowance(request.reviewAllowance() == null || request.reviewAllowance() < 0
                        ? 2 : request.reviewAllowance())
                .reviewsEnabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        project = projectRepository.save(project);

        // Default new-project dialogue language: Hinglish (hi-Latn-IN, seeded in llm-gateway V78).
        // The deployment's single-market context is India and eleven_multilingual_v2 needs an
        // explicit language_code hint to render romanized Hindi text with a Hindi accent rather
        // than English -- leaving this null (previous behavior) let the model over-rely on its
        // English prior for anything romanized. Existing projects with a non-null value are
        // untouched (no bulk migration); creators can still change it in the project settings.
        projectConfigRepository.save(ProjectConfig.builder()
                .projectId(project.getId())
                .dialogueLanguage("hi-Latn-IN")
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
     * shots) let it re-fetch whichever earlier state the user navigates back to.
     * <p>
     * Collapses by {@code lockedIdeaId}, keeping only the most recently updated row per idea.
     * {@link #createFromLockedIdea} is idempotent by lockedIdeaId going forward, but rows created
     * before that guard existed (a caller retry that raced a rollback in
     * {@code ProjectRequirementIdeaService.lockOption}) can still have a stale, abandoned sibling
     * sitting at whatever status it reached before the retry moved on -- that sibling is real data
     * (not deleted here), just not what the picker should surface as "the" project for that idea. */
    @Transactional(readOnly = true)
    public List<ProjectView> list(UUID tenantId) {
        return projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .collect(Collectors.toMap(
                        Project::getLockedIdeaId,
                        p -> p,
                        (a, b) -> a.getUpdatedAt().isAfter(b.getUpdatedAt()) ? a : b,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .sorted(java.util.Comparator.comparing(Project::getCreatedAt).reversed())
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

    /** Called by creative-planning-service after it creates a new LockedIdea for this project (a
     * creator picking a different idea) -- see ProjectIdeaService#switchToOption there. Just a
     * pointer update: repoints which idea is "current" for this project. Script/Screenplay/Shot
     * generation each stamp their own row with whatever this points to at the moment they run, so
     * this alone is what a later regenerate needs to pick up the new idea. */
    @Transactional
    public ProjectView switchLockedIdea(UUID tenantId, UUID projectId, UUID lockedIdeaId) {
        Project project = requireProject(tenantId, projectId);
        project.setLockedIdeaId(lockedIdeaId);
        project.setUpdatedAt(OffsetDateTime.now());
        return toView(projectRepository.save(project));
    }

    Project requireProject(UUID tenantId, UUID projectId) {
        return projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
    }

    /** Generates one the first time a project is locked, returns the existing one on every later
     * call -- same "possession of the token is the authorization" convention creative-planning-
     * service's ProjectRequirement.shareToken already uses, deliberately non-expiring (unlike that
     * one) since a locked package's review link is meant to stay usable indefinitely. */
    @Transactional
    public String ensureClientReviewToken(UUID tenantId, UUID projectId) {
        Project project = requireProject(tenantId, projectId);
        if (project.getClientReviewToken() == null) {
            project.setClientReviewToken(UUID.randomUUID().toString().replace("-", ""));
            project.setUpdatedAt(OffsetDateTime.now());
            projectRepository.save(project);
        }
        return project.getClientReviewToken();
    }

    @Transactional(readOnly = true)
    public UUID getChatSessionId(UUID tenantId, UUID projectId) {
        return requireProject(tenantId, projectId).getChatSessionId();
    }

    @Transactional
    public void attachChatSession(UUID tenantId, UUID projectId, UUID chatSessionId) {
        Project project = requireProject(tenantId, projectId);
        if (project.getChatSessionId() == null) {
            project.setChatSessionId(chatSessionId);
            project.setUpdatedAt(OffsetDateTime.now());
            projectRepository.save(project);
        }
    }

    /** Every service backing pre-production-service's public/{token} client review page resolves
     * the project this way first, then calls the exact same tenant-scoped methods (getScript,
     * getScreenplay, ...) every authenticated page already uses -- no parallel "public" read path. */
    @Transactional(readOnly = true)
    public ProjectIdentity resolveByClientReviewToken(String token) {
        Project project = requireByClientReviewToken(token);
        return new ProjectIdentity(project.getTenantId(), project.getId());
    }

    @Transactional(readOnly = true)
    public ProjectView getByClientReviewToken(String token) {
        return toView(requireByClientReviewToken(token));
    }

    UUID getChatSessionIdByClientReviewToken(String token) {
        return requireByClientReviewToken(token).getChatSessionId();
    }

    private Project requireByClientReviewToken(String token) {
        return projectRepository.findByClientReviewToken(token)
                .orElseThrow(() -> PreProductionException.notFound("No project for this review link"));
    }

    public record ProjectIdentity(UUID tenantId, UUID projectId) {
    }

    /** Creator control over a project's client reviews: change the included allowance and/or turn
     * reviews on/off on demand. Null fields are left unchanged. */
    @org.springframework.transaction.annotation.Transactional
    public ProjectView updateReviewSettings(UUID tenantId, UUID projectId, Integer reviewAllowance, Boolean reviewsEnabled) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("Unknown project: " + projectId));
        if (reviewAllowance != null) {
            project.setReviewAllowance(Math.max(0, reviewAllowance));
        }
        if (reviewsEnabled != null) {
            project.setReviewsEnabled(reviewsEnabled);
        }
        project.setUpdatedAt(OffsetDateTime.now());
        return toView(projectRepository.save(project));
    }

    /** Manual creator toggle for whether the client can download the assembled final video from
     * their public review page -- see {@link Project#isFinalVideoDownloadUnlocked}. The client
     * can preview the video regardless; this gates the download link only. */
    @org.springframework.transaction.annotation.Transactional
    public ProjectView updateFinalVideoDownloadUnlocked(UUID tenantId, UUID projectId, boolean unlocked) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("Unknown project: " + projectId));
        project.setFinalVideoDownloadUnlocked(unlocked);
        project.setUpdatedAt(OffsetDateTime.now());
        return toView(projectRepository.save(project));
    }

    private ProjectView toView(Project project) {
        return new ProjectView(
                project.getId(), project.getName(), project.getLockedIdeaId(),
                project.getBudgetTier(), project.getStatus(), project.getCreatedAt(),
                project.getReviewAllowance(), project.isReviewsEnabled(),
                project.isFinalVideoDownloadUnlocked());
    }
}
