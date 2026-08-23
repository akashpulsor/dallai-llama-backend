package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotPlanIssue;
import com.dalai.llama.preprod.dto.ShotPlanIssueView;
import com.dalai.llama.preprod.repository.ShotPlanIssueRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic (plain Java, no LLM) shot-plan quality checks -- restores creator-service's real
 * ShotPlanCriticService.checkCoverageCompleteness/checkShotTypeVariety. Informational only: these
 * findings never block generation or dispatch (critic-service's AI pre-flight critique already
 * owns that gate at dispatch time) -- they're a visibility layer the creator reads and decides
 * whether to act on.
 */
@Service
public class ShotPlanQualityService {

    private final ShotRepository shotRepository;
    private final ShotPlanIssueRepository shotPlanIssueRepository;
    private final ProjectService projectService;

    public ShotPlanQualityService(ShotRepository shotRepository, ShotPlanIssueRepository shotPlanIssueRepository, ProjectService projectService) {
        this.shotRepository = shotRepository;
        this.shotPlanIssueRepository = shotPlanIssueRepository;
        this.projectService = projectService;
    }

    @Transactional
    public void refresh(UUID tenantId, UUID projectId) {
        shotPlanIssueRepository.deleteByProjectId(projectId);
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        OffsetDateTime now = OffsetDateTime.now();

        for (Shot shot : shots) {
            checkCoverageCompleteness(tenantId, projectId, shot, now);
        }
        checkShotTypeVariety(tenantId, projectId, shots, now);
    }

    @Transactional(readOnly = true)
    public List<ShotPlanIssueView> list(UUID tenantId, UUID projectId) {
        projectService.requireProject(tenantId, projectId);
        return shotPlanIssueRepository.findByProjectIdOrderByShotRefAsc(projectId).stream()
                .map(i -> new ShotPlanIssueView(i.getShotRef(), i.getCategory(), i.getMessage()))
                .collect(Collectors.toList());
    }

    private void checkCoverageCompleteness(UUID tenantId, UUID projectId, Shot shot, OffsetDateTime now) {
        if (shot.getShotType() == ShotType.MOTION_GRAPHIC) {
            return;
        }
        if (shot.getLightingMood() == null) {
            save(tenantId, projectId, shot.getShotRef(), "COVERAGE", "No lighting mood set.", now);
        }
        boolean hasCamera = (shot.getCameraAngle() != null && !shot.getCameraAngle().isBlank())
                || (shot.getCameraMovement() != null && !shot.getCameraMovement().isBlank());
        if (!hasCamera) {
            save(tenantId, projectId, shot.getShotRef(), "COVERAGE", "No camera angle or movement set.", now);
        }
        boolean hasCharacter = shot.getPrimaryCharacterKey() != null && !shot.getPrimaryCharacterKey().isBlank();
        boolean hasAction = shot.getAction() != null && !shot.getAction().isBlank();
        if (!hasCharacter && !hasAction) {
            save(tenantId, projectId, shot.getShotRef(), "COVERAGE", "No character and no action -- nothing established to happen in this shot.", now);
        }
    }

    private void checkShotTypeVariety(UUID tenantId, UUID projectId, List<Shot> shots, OffsetDateTime now) {
        for (int i = 1; i < shots.size(); i++) {
            Shot previous = shots.get(i - 1);
            Shot current = shots.get(i);
            if (current.getShotType() != ShotType.PRODUCT_HERO || previous.getShotType() != ShotType.PRODUCT_HERO) {
                continue;
            }
            if (current.getProductShotType() != null && current.getProductShotType().equalsIgnoreCase(previous.getProductShotType())) {
                save(tenantId, projectId, current.getShotRef(), "VARIETY",
                        "Same product shot type (\"" + current.getProductShotType() + "\") as the previous shot -- consider varying coverage.", now);
            }
        }
    }

    private void save(UUID tenantId, UUID projectId, String shotRef, String category, String message, OffsetDateTime now) {
        shotPlanIssueRepository.save(ShotPlanIssue.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .shotRef(shotRef)
                .category(category)
                .message(message)
                .createdAt(now)
                .build());
    }
}
