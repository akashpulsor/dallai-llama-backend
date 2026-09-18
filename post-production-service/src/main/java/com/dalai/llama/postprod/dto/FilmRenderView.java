package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.entity.FilmRender;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
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
        /**
         * The exact cut of each shot this film was built from, newest-film-first order preserved.
         *
         * <p>Sent down raw so the page can compare it against the shots' current cuts and say "this
         * film is out of date" itself. A film is a built artifact, not a live view: accepting a new
         * cut cannot change an .mp4 that already exists, and until now nothing said so -- a creator
         * who fixed a shot and pressed play saw the old film and reasonably concluded the fix had
         * not worked.
         *
         * <p>Raw ids rather than a computed "stale" boolean on purpose. The page already holds the
         * current cuts; comparing two lists is its job, and a flag would have to be recomputed on
         * every read and would go wrong quietly when it was not.
         */
        List<UUID> sourceVersionIds,
        int filmsAhead,
        /** A rough total wait in seconds, or null when no join has finished yet and there is
         * nothing honest to estimate from. */
        Long estimatedWaitSeconds
) {

    /** Stored comma-separated; a film from before this was recorded simply has none. */
    private static List<UUID> parseVersionIds(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> {
                    try {
                        return UUID.fromString(part);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

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
                parseVersionIds(render.getSourceVersionIds()),
                filmsAhead,
                estimatedWaitSeconds);
    }
}
