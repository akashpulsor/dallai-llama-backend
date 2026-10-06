package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.entity.SoundLayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a layer lands in the film: its shot's start (every earlier cut, in film order) plus its offset. */
class FilmSoundtrackTest {

    private final UUID shot1 = UUID.randomUUID(), shot2 = UUID.randomUUID(), shot3 = UUID.randomUUID();

    private static FilmSoundtrack.ShotSpan span(UUID shot, String seconds) {
        return new FilmSoundtrack.ShotSpan(shot, new BigDecimal(seconds));
    }

    private static SoundLayer layer(UUID shot, int offsetMs, boolean included) {
        return SoundLayer.builder().layerId(UUID.randomUUID()).shotId(shot).kind(SoundLayerKind.SOUND_EFFECT)
                .offsetMs(offsetMs).included(included).build();
    }

    @Test
    void aBellOneAndAHalfSecondsIntoTheThirdShotStartsAfterTheFirstTwoShots() {
        SoundLayer bell = layer(shot3, 1500, true);

        List<FilmSoundtrack.Placement> placed = FilmSoundtrack.place(
                List.of(span(shot1, "4.0"), span(shot2, "6.5"), span(shot3, "5.0")), List.of(bell));

        assertThat(placed).singleElement().satisfies(p -> {
            assertThat(p.layer()).isSameAs(bell);
            assertThat(p.startMs()).isEqualTo(4000 + 6500 + 1500);
        });
    }

    @Test
    void reorderingTheShotsMovesTheLayerWithItsShot() {
        SoundLayer bell = layer(shot3, 0, true);

        List<FilmSoundtrack.Placement> placed = FilmSoundtrack.place(
                List.of(span(shot3, "5.0"), span(shot1, "4.0"), span(shot2, "6.5")), List.of(bell));

        assertThat(placed.get(0).startMs()).isZero();
    }

    @Test
    void switchedOffLayersAndLayersOfShotsNoLongerInTheFilmAreLeftOut() {
        SoundLayer off = layer(shot1, 0, false);
        SoundLayer orphan = layer(UUID.randomUUID(), 0, true);

        assertThat(FilmSoundtrack.place(List.of(span(shot1, "4.0")), List.of(off, orphan))).isEmpty();
    }

    @Test
    void aLayerMayRunPastItsShotButNotStartAfterTheFilmEnds() {
        SoundLayer pastShot = layer(shot1, 5000, true);
        SoundLayer pastFilm = layer(shot2, 9000, true);

        List<FilmSoundtrack.Placement> placed = FilmSoundtrack.place(
                List.of(span(shot1, "4.0"), span(shot2, "6.5")), List.of(pastShot, pastFilm));

        assertThat(placed).extracting(FilmSoundtrack.Placement::startMs).containsExactly(5000L);
    }

    @Test
    void layersComeBackInFilmOrder() {
        SoundLayer late = layer(shot2, 1000, true);
        SoundLayer early = layer(shot1, 500, true);

        assertThat(FilmSoundtrack.place(List.of(span(shot1, "4.0"), span(shot2, "6.5")), List.of(late, early)))
                .extracting(FilmSoundtrack.Placement::layer).containsExactly(early, late);
    }
}
