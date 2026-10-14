package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.CloneAudioService.CloneAudioView;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews.ShotDialogueBeatView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A multi-line shot carries every line's take, each where its line was planned to start. */
class ShotTakesTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-14T10:00:00Z");
    private final UUID shot = UUID.randomUUID();
    private final UUID meera = UUID.randomUUID();
    private final UUID ravi = UUID.randomUUID();
    // She looks up first; she asks at 1.2s, he answers at 3.5s.
    private final List<ShotDialogueBeatView> beats = List.of(
            new ShotDialogueBeatView(ravi, 1, new BigDecimal("3.5"), BigDecimal.ONE, "Theek hai.", "ravi", null),
            new ShotDialogueBeatView(meera, 0, new BigDecimal("1.2"), BigDecimal.ONE, "Ho gaya?", "meera", null));

    private CloneAudioView take(UUID beatId, String text, String url, int minutesAfter) {
        return new CloneAudioView(shot, beatId, text, "cloned", "voice", url, 900, T0.plusMinutes(minutesAfter), false);
    }

    @Test
    void eachLinesTakeIsPlacedAtItsOwnStart_inTheOrderSpoken() {
        List<ShotTakes.TimedTake> placed = ShotTakes.choose(beats, List.of(
                take(ravi, "Theek hai.", "https://media/ravi.mp3", 2),
                take(meera, "Ho gaya?", "https://media/meera.mp3", 1)));

        assertThat(placed).extracting(ShotTakes.TimedTake::audioUrl).containsExactly("https://media/meera.mp3", "https://media/ravi.mp3");
        assertThat(placed).extracting(ShotTakes.TimedTake::startSeconds).containsExactly(1.2, 3.5);
    }

    @Test
    void aLineRecordedAgainUsesItsNewestTake_andATakeOfOldWordsIsLeftOut() {
        List<ShotTakes.TimedTake> placed = ShotTakes.choose(beats, List.of(
                take(meera, "Ho gaya?", "https://media/meera-1.mp3", 1),
                take(meera, "Ho gaya?", "https://media/meera-2.mp3", 5),
                take(ravi, "Haan, theek hai.", "https://media/ravi-old-words.mp3", 6)));

        assertThat(placed).extracting(ShotTakes.TimedTake::audioUrl).containsExactly("https://media/meera-2.mp3");
    }

    @Test
    void aWholeLineTakeNewerThanThePerLineTakesReplacesThem_atTheFirstLine() {
        List<ShotTakes.TimedTake> placed = ShotTakes.choose(beats, List.of(
                take(meera, "Ho gaya?", "https://media/meera.mp3", 1),
                take(null, "Ho gaya? Theek hai.", "https://media/whole.mp3", 9)));

        assertThat(placed).hasSize(1);
        assertThat(placed.get(0).audioUrl()).isEqualTo("https://media/whole.mp3");
        assertThat(placed.get(0).startSeconds()).isEqualTo(1.2);
    }

    @Test
    void aShotWithNoBeatsCarriesItsTakeFromTheFirstFrame() {
        List<ShotTakes.TimedTake> placed = ShotTakes.choose(List.of(), List.of(take(null, "Mumbai wakes up early.", "https://media/vo.mp3", 1)));

        assertThat(placed).extracting(ShotTakes.TimedTake::startSeconds).containsExactly(0.0);
        assertThat(ShotTakes.choose(beats, List.of())).isEmpty();
    }
}
