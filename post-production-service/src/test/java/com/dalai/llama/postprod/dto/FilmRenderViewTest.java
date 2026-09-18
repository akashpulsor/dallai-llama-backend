package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.domain.entity.FilmRenderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cuts a film was built from, sent down so the page can tell it is out of date.
 *
 * <p>A film is a built artifact: accepting a new cut cannot change an .mp4 that already exists.
 * Nothing said so, and a creator who fixed a shot, pressed play and heard the old cut reasonably
 * concluded the fix had not worked. The page compares these against the shots' current cuts.
 *
 * <p>Ids rather than a server-computed "stale" flag: the page already holds the current cuts, so
 * this is two lists, and a flag would have to be recomputed on every read.
 */
class FilmRenderViewTest {

    private static FilmRender render(String sourceVersionIds) {
        return FilmRender.builder()
                .renderId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .status(FilmRenderStatus.COMPLETED)
                .sourceVersionIds(sourceVersionIds)
                .build();
    }

    @Test
    void carriesTheCutItWasBuiltFrom() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        FilmRenderView view = FilmRenderView.of(render(first + "," + second), "https://minio/film.mp4");

        assertEquals(List.of(first, second), view.sourceVersionIds());
    }

    @Test
    void toleratesSpacingAroundTheStoredIds() {
        UUID only = UUID.randomUUID();

        FilmRenderView view = FilmRenderView.of(render(" " + only + " , "), "https://minio/film.mp4");

        assertEquals(List.of(only), view.sourceVersionIds());
    }

    /** Films joined before this was recorded simply have none -- the page then says nothing. */
    @Test
    void aFilmFromBeforeThisWasRecordedHasNoIds() {
        assertTrue(FilmRenderView.of(render(null), "u").sourceVersionIds().isEmpty());
        assertTrue(FilmRenderView.of(render(""), "u").sourceVersionIds().isEmpty());
    }

    /** Never throw over a malformed row: the film is still watchable and the panel still loads. */
    @Test
    void skipsAnythingThatIsNotAnId() {
        UUID good = UUID.randomUUID();

        FilmRenderView view = FilmRenderView.of(render("not-a-uuid," + good), "u");

        assertEquals(List.of(good), view.sourceVersionIds());
    }
}
