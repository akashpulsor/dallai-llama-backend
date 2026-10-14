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
import java.util.Map;
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
        Optional<PostProductionFilmClient.PublishedFilm> film = filmClient.getShowcaseFilm(tenantId, projectId);
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

    /** The film's poster on the public profile, presigned for an hour: the frame the creator chose,
     * else the first shot's finished frame (else its storyboard). Null when the project has none. */
    String posterUrl(UUID projectId) {
        List<com.dalai.llama.preprod.domain.entity.ShotImage> frames = posterFrames(projectId);
        UUID chosen = projectRepository.findById(projectId).map(com.dalai.llama.preprod.domain.entity.Project::getPosterImageId).orElse(null);
        return frames.stream().filter(f -> java.util.Objects.equals(f.getId(), chosen) && chosen != null).findFirst()
                .or(() -> frames.stream().findFirst())
                .map(this::presigned).orElse(null);
    }

    /** The frames a film's thumbnail can be chosen from, in shot order: each shot's finished frames
     * (its storyboard where none is finished yet) and any motion-graphic frame. */
    List<com.dalai.llama.preprod.domain.entity.ShotImage> posterFrames(UUID projectId) {
        List<com.dalai.llama.preprod.domain.entity.ShotImage> frames = new java.util.ArrayList<>();
        for (var shot : shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)) {
            var images = shotImageRepository.findByShotId(shot.getId()).stream()
                    .filter(i -> i.getBucket() != null && i.getObjectKey() != null).toList();
            var finished = images.stream().filter(i -> i.getKind() == com.dalai.llama.preprod.domain.ShotImageKind.PRODUCTION
                    || i.getKind() == com.dalai.llama.preprod.domain.ShotImageKind.MOTION_GRAPHIC).toList();
            frames.addAll(finished.isEmpty()
                    ? images.stream().filter(i -> i.getKind() == com.dalai.llama.preprod.domain.ShotImageKind.STORYBOARD).toList()
                    : finished);
        }
        return frames;
    }

    /** The frames to choose the thumbnail from, the chosen one marked. */
    public List<PosterFrameView> posterFrameViews(UUID tenantId, UUID projectId) {
        var project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        Map<UUID, Integer> shotNumbers = new java.util.HashMap<>();
        shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).forEach(s -> shotNumbers.put(s.getId(), s.getShotNumber()));
        List<com.dalai.llama.preprod.domain.entity.ShotImage> frames = posterFrames(projectId);
        UUID chosen = project.getPosterImageId() != null && frames.stream().anyMatch(f -> project.getPosterImageId().equals(f.getId()))
                ? project.getPosterImageId() : frames.stream().findFirst().map(com.dalai.llama.preprod.domain.entity.ShotImage::getId).orElse(null);
        return frames.stream().map(f -> new PosterFrameView(f.getId(), shotNumbers.get(f.getShotId()), f.getKind().name(),
                presigned(f), java.util.Objects.equals(f.getId(), chosen))).toList();
    }

    /** Chooses the film's thumbnail; null goes back to the default (the first shot's frame). */
    @org.springframework.transaction.annotation.Transactional
    public List<PosterFrameView> choosePoster(UUID tenantId, UUID projectId, UUID imageId) {
        var project = projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        if (imageId != null && posterFrames(projectId).stream().noneMatch(f -> imageId.equals(f.getId()))) {
            throw PreProductionException.badRequest("That frame isn't one of this film's shots");
        }
        project.setPosterImageId(imageId);
        projectRepository.save(project);
        return posterFrameViews(tenantId, projectId);
    }

    /** One frame offered as the thumbnail. */
    public record PosterFrameView(UUID imageId, Integer shotNumber, String kind, String url, boolean chosen) {
    }

    private String presigned(com.dalai.llama.preprod.domain.entity.ShotImage frame) {
        try {
            return publicMinioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET).bucket(frame.getBucket()).object(frame.getObjectKey())
                    .expiry(1, java.util.concurrent.TimeUnit.HOURS).build());
        } catch (Exception ex) {
            return null;
        }
    }

    /** How many recent projects {@link #films} looks at; each needs one post-production lookup. */
    static final int FILM_LOOKBACK = 30;

    /** A creator's finished films, newest projects first, for "publish to YouTube" pickers
     * (CREATOR_SHOWCASE.md rule 34). No download links: those are fetched per film when needed. */
    public List<FilmSummary> films(UUID tenantId) {
        return projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .limit(FILM_LOOKBACK)
                .flatMap(project -> filmClient.getShowcaseFilm(tenantId, project.getId()).stream()
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
