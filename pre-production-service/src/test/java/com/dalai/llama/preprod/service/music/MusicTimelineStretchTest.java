package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.service.music.MusicDirectorPlannerService.ShotWindow;
import com.dalai.llama.preprod.service.music.MusicDirectorPlannerService.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 60s film whose shots were planned to 35s (they are rendered short and slowed to fill) was
 * scored as a 35s piece. The score is composed for the film's target length instead.
 */
class MusicTimelineStretchTest {

    private final Timeline planned = new Timeline(List.of(
            new ShotWindow(new Shot(), 0, 10, false),
            new ShotWindow(new Shot(), 10, 30, true)), 30);

    @Test
    void shotsShorterThanTheFilmAreSpreadOverItsWholeLength() {
        Timeline film = planned.stretchedTo(60);

        assertThat(film.totalSeconds()).isEqualTo(60);
        assertThat(film.windows()).extracting(ShotWindow::start).containsExactly(0.0, 20.0);
        assertThat(film.windows()).extracting(ShotWindow::end).containsExactly(20.0, 60.0);
        assertThat(film.windows().get(1).spoken()).isTrue();
    }

    @Test
    void aTimelineAlreadyAsLongAsTheFilmOrWithNoTargetIsUnchanged() {
        assertThat(planned.stretchedTo(30)).isSameAs(planned);
        assertThat(planned.stretchedTo(0)).isSameAs(planned);
    }
}
