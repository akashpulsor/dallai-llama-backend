package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.dto.ShotDesignReadyProjectView;
import com.dalai.llama.preprod.dto.ShotDesignSummaryView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Backs {@code GET /v1/projects/shot-design-ready} -- see {@link ShotDesignReadyProjectView}'s
 * class comment for why this exists and what it deliberately leaves out. */
@Service
public class ShotDesignReadyProjectService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 60;

    private final ProjectService projectService;
    private final ShotListGenerationService shotListGenerationService;
    private final ShotImageService shotImageService;

    public ShotDesignReadyProjectService(
            ProjectService projectService,
            ShotListGenerationService shotListGenerationService,
            ShotImageService shotImageService
    ) {
        this.projectService = projectService;
        this.shotListGenerationService = shotListGenerationService;
        this.shotImageService = shotImageService;
    }

    /** projectService.list() already returns newest-first, so the first {@code safeLimit}
     * shot-bearing projects found are the same ones a caller would expect from "recent projects
     * ready for post-production" -- no separate sort needed here. */
    public List<ShotDesignReadyProjectView> list(UUID tenantId, Integer limit) {
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));
        List<ProjectView> projects = projectService.list(tenantId);
        List<ShotDesignReadyProjectView> results = new ArrayList<>();
        for (ProjectView project : projects) {
            if (results.size() >= safeLimit) {
                break;
            }
            List<ShotView> shots = shotListGenerationService.list(tenantId, project.id());
            if (shots.isEmpty()) {
                continue;
            }
            List<ShotDesignSummaryView> summaries = shots.stream()
                    .map(shot -> toSummary(tenantId, shot))
                    .toList();
            results.add(new ShotDesignReadyProjectView(
                    project.id(),
                    project.name(),
                    project.status() == null ? null : project.status().name(),
                    project.createdAt(),
                    shots.size(),
                    summaries
            ));
        }
        return results;
    }

    /** One extra DB read per shot (ShotImageService.list is a plain in-process call, same service,
     * no network hop) -- fine at this scale, see the class-level and DTO javadocs. Last-write-wins
     * on a duplicate kind is impossible in practice (ShotImageService only ever keeps one row per
     * shot+kind), (a, b) -> a is just Collectors.toMap's required merge function. */
    private ShotDesignSummaryView toSummary(UUID tenantId, ShotView shot) {
        Map<ShotImageKind, String> imagesByKind = shotImageService.list(tenantId, shot.id()).stream()
                .collect(Collectors.toMap(ShotImageView::kind, ShotImageView::signedUrl, (a, b) -> a));
        return new ShotDesignSummaryView(
                shot.shotNumber(),
                shot.cameraAngle(),
                shot.cameraMovement(),
                shot.lensSuggestion(),
                imagesByKind.get(ShotImageKind.STORYBOARD),
                imagesByKind.get(ShotImageKind.LIGHTING),
                imagesByKind.get(ShotImageKind.CAMERA_PLAN)
        );
    }
}
