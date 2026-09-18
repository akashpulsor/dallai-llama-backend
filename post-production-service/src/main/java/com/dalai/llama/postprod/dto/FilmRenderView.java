package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.entity.FilmRender;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The whole film, as the page sees it.
 *
 * <p>{@code width} and {@code height} travel with it so the player can shape itself to the cut
 * rather than guessing and letterboxing a vertical film into a landscape box.
 *
 * @param published whether the client's review page shows this. Off means invisible there, not
 *                  merely undownloadable.
 */
public record FilmRenderView(
        UUID renderId,
        UUID projectId,
        String status,
        Integer shotCount,
        String videoUrl,
        BigDecimal durationSeconds,
        Integer width,
        Integer height,
        boolean published,
        OffsetDateTime publishedAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        /** Films queued in front of this one, across every tenant -- that is the queue actually
         * being waited in, since joins run one at a time. Zero once this film is being worked on. */
        int filmsAhead,
        /** A rough total wait in seconds, or null when no join has finished yet and there is
         * nothing honest to estimate from. */
        Long estimatedWaitSeconds
) {

    public static FilmRenderView of(FilmRender render, String videoUrl) {
        return of(render, videoUrl, 0, null);
    }

    public static FilmRenderView of(FilmRender render, String videoUrl, int filmsAhead, Long estimatedWaitSeconds) {
        return new FilmRenderView(
                render.getRenderId(),
                render.getProjectId(),
                render.getStatus() == null ? null : render.getStatus().name(),
                render.getShotCount(),
                videoUrl,
                render.getDurationSeconds(),
                render.getWidth(),
                render.getHeight(),
                render.isPublished(),
                render.getPublishedAt(),
                render.getLastError(),
                render.getCreatedAt(),
                render.getCompletedAt(),
                filmsAhead,
                estimatedWaitSeconds);
    }
}
