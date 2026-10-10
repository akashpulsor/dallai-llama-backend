package com.dalai.llama.preprod.service.showcase;

import com.dalai.llama.preprod.domain.ReviewActor;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.repository.ClientReviewSessionRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ReviewCommentRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.postproduction.PostProductionFilmClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** What tenant-service's showcase needs to know about one project's film: whether it is ready,
 * its size and length (aspect ratio, link matching), whether the client paid and agreed to
 * marketing use, and raw counts of the work done in the app. Raw facts only: the thresholds that
 * turn them into "verified funded" live in tenant-service config (CREATOR_SHOWCASE.md rule 8). */
@Service
public class ShowcaseSourceService {

    private final ProjectRepository projectRepository;
    private final PostProductionFilmClient filmClient;
    private final ShotImageRepository shotImageRepository;
    private final ClientReviewSessionRepository reviewSessionRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final CreativeDirectionRepository creativeDirectionRepository;

    public ShowcaseSourceService(ProjectRepository projectRepository,
                                 PostProductionFilmClient filmClient,
                                 ShotImageRepository shotImageRepository,
                                 ClientReviewSessionRepository reviewSessionRepository,
                                 ReviewCommentRepository reviewCommentRepository,
                                 CreativeDirectionRepository creativeDirectionRepository) {
        this.projectRepository = projectRepository;
        this.filmClient = filmClient;
        this.shotImageRepository = shotImageRepository;
        this.reviewSessionRepository = reviewSessionRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.creativeDirectionRepository = creativeDirectionRepository;
    }

    public ShowcaseSourceView source(UUID tenantId, UUID projectId) {
        Project project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Optional<PostProductionFilmClient.PublishedFilm> film = filmClient.getPublishedFilm(tenantId, projectId);
        return new ShowcaseSourceView(
                project.getId(),
                project.getName(),
                project.getStatus().name(),
                film.isPresent(),
                film.map(PostProductionFilmClient.PublishedFilm::completedAt).orElse(null),
                film.map(PostProductionFilmClient.PublishedFilm::durationSeconds).orElse(null),
                film.map(PostProductionFilmClient.PublishedFilm::width).orElse(null),
                film.map(PostProductionFilmClient.PublishedFilm::height).orElse(null),
                project.getClientLockedAt(),
                project.getMarketingTermsVersion(),
                project.getMarketingTermsAcceptedAt(),
                shotImageRepository.countForProject(projectId),
                reviewSessionRepository.countByProjectId(projectId),
                reviewCommentRepository.countByProjectId(projectId),
                creativeDirectionRepository.countByProjectIdAndApprovedVia(projectId, ReviewActor.CLIENT),
                film.map(PostProductionFilmClient.PublishedFilm::videoUrl).orElse(null));
    }

    /** The wire shape tenant-service reads. {@code downloadUrl} is a short-lived presigned link to
     * the published film: for the creator's upload kit and the SELF video host, never stored. */
    public record ShowcaseSourceView(
            UUID projectId,
            String projectName,
            String projectStatus,
            boolean filmReady,
            OffsetDateTime renderedAt,
            BigDecimal durationSeconds,
            Integer width,
            Integer height,
            OffsetDateTime clientLockedAt,
            String marketingTermsVersion,
            OffsetDateTime marketingTermsAcceptedAt,
            long shotImages,
            long clientReviewSessions,
            long clientComments,
            long clientApprovals,
            String downloadUrl
    ) {
    }
}
