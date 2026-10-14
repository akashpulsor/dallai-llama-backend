package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.preproduction.PreProductionViews.ShotDialogueBeatView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A recorded take is laid on where its line was planned to start, not on the first frame. */
class TakeTimingTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    // She looks up first; the line comes at 1.2s, the reply at 3.5s.
    private final List<ShotDialogueBeatView> beats = List.of(
            new ShotDialogueBeatView(second, 1, new BigDecimal("3.5"), BigDecimal.ONE, "Theek hai.", "ravi", null),
            new ShotDialogueBeatView(first, 0, new BigDecimal("1.2"), BigDecimal.ONE, "Ho gaya?", "meera", null));

    @Test
    void aTakeForOneBeatStartsWhereThatBeatIsPlanned() {
        assertThat(TakeTiming.startSeconds(beats, second)).isEqualTo(3.5);
    }

    @Test
    void aTakeOfTheWholeLineStartsAtTheShotsFirstBeat() {
        assertThat(TakeTiming.startSeconds(beats, null)).isEqualTo(1.2);
        // A beat id that no longer matches (the line was rewritten) falls back the same way.
        assertThat(TakeTiming.startSeconds(beats, UUID.randomUUID())).isEqualTo(1.2);
    }

    @Test
    void aShotWithNoBeatsStartsAtTheFirstFrame() {
        assertThat(TakeTiming.startSeconds(List.of(), null)).isZero();
        assertThat(TakeTiming.startSeconds(List.of(new ShotDialogueBeatView(first, 0, null, null, "x", "a", null)), first)).isZero();
    }
}
