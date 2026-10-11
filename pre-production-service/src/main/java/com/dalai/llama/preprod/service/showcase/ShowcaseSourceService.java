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
import java.util.List;
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
    private final com.dalai.llama.preprod.repository.ShotRepository shotRepository;
    private final io.minio.MinioClient publicMinioClient;

    public ShowcaseSourceService(ProjectRepository projectRepository,
                                 PostProductionFilmClient filmClient,
                                 ShotImageRepository shotImageRepository,
                                 ClientReviewSessionRepository reviewSessionRepository,
                                 ReviewCommentRepository reviewCommentRepository,
                                 CreativeDirectionRepository creativeDirectionRepository,
                                 com.dalai.llama.preprod.repository.ShotRepository shotRepository,
                                 @org.springframework.beans.factory.annotation.Qualifier("publicMinioClient") io.minio.MinioClient publicMinioClient) {
        this.shotRepository = shotRepository;
        this.publicMinioClient = publicMinioClient;
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
                film.map(PostProductionFilmClient.PublishedFilm::videoUrl).orElse(null),
                film.map(PostProductionFilmClient.PublishedFilm::stampedVideoUrl).orElse(null),
                posterUrl(projectId));
    }

    /** The first shot's finished frame (else its storyboard), presigned for an hour: the film's poster
     * where YouTube has no thumbnail to give (a private upload). Null when the project has none. */
    String posterUrl(UUID projectId) {
        for (var shot : shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)) {
            var images = shotImageRepository.findByShotId(shot.getId());
            var frame = images.stream().filter(i -> i.getKind() == com.dalai.llama.preprod.domain.ShotImageKind.PRODUCTION).findFirst()
                    .or(() -> images.stream().filter(i -> i.getKind() == com.dalai.llama.preprod.domain.ShotImageKind.STORYBOARD).findFirst());
            if (frame.isPresent() && frame.get().getBucket() != null && frame.get().getObjectKey() != null) {
                try {
                    return publicMinioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET).bucket(frame.get().getBucket()).object(frame.get().getObjectKey())
                            .expiry(1, java.util.concurrent.TimeUnit.HOURS).build());
                } catch (Exception ex) {
                    return null;
                }
            }
        }
        return null;
    }

    /** How many recent projects {@link #films} looks at; each needs one post-production lookup. */
    static final int FILM_LOOKBACK = 30;

    /** A creator's finished films, newest projects first, for "publish to YouTube" pickers
     * (CREATOR_SHOWCASE.md rule 34). No download links: those are fetched per film when needed. */
    public List<FilmSummary> films(UUID tenantId) {
        return projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .limit(FILM_LOOKBACK)
                .flatMap(project -> filmClient.getPublishedFilm(tenantId, project.getId()).stream()
                        .map(film -> new FilmSummary(project.getId(), project.getName(), film.completedAt(), film.durationSeconds(),
                                film.width(), film.height(), project.getClientLockedAt(), project.getMarketingTermsAcceptedAt())))
                .toList();
    }

    public record FilmSummary(
            UUID projectId,
            String projectName,
            OffsetDateTime renderedAt,
            BigDecimal durationSeconds,
            Integer width,
            Integer height,
            OffsetDateTime clientLockedAt,
            OffsetDateTime marketingTermsAcceptedAt
    ) {
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
            String downloadUrl,
            /* The same film with the Dalaillama mark: what every upload from the platform uses. Null
             * until post-production has stamped it (shortly after publishing). */
            String stampedDownloadUrl,
            /* The first shot's frame, presigned for an hour; null when there is none. */
            String posterUrl
    ) {
    }
}
