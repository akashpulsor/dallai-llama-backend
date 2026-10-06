package com.dalai.llama.postprod.service.clip;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The ffmpeg graph that lays the score and the sound layers under the film. */
class SoundtrackFilterTest {

    @Test
    void theScoreAndABellAreEachPlacedLevelledAndFadedUnderTheFilmsOwnSound() {
        String filter = FfmpegClipProcessor.soundtrackFilter(List.of(
                new FilmAudioTrack("https://minio/score.mp3", 0, -13.15, 0, 0, null),
                new FilmAudioTrack("https://minio/bell.wav", 12000, -4.0, 0, 150, 3.2)));

        assertThat(filter).isEqualTo(
                "[0:a]aformat=sample_rates=48000:channel_layouts=stereo[f];"
                        + "[1:a]aformat=sample_rates=48000:channel_layouts=stereo,volume=-13.15dB[t1];"
                        + "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,volume=-4.0dB"
                        + ",afade=t=out:st=3.05:d=0.15,adelay=12000:all=1[t2];"
                        + "[f][t1][t2]amix=inputs=3:duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.95[a]");
    }

    @Test
    void aMusicCueEasesInAndOut() {
        String filter = FfmpegClipProcessor.soundtrackFilter(List.of(
                new FilmAudioTrack("https://minio/cue.mp3", 2500, -14.0, 500, 1500, 10.0)));

        assertThat(filter).contains("afade=t=in:st=0:d=0.5,afade=t=out:st=8.5:d=1.5,adelay=2500:all=1[t1]");
    }

    @Test
    void aFadeOutLongerThanTheSoundIsSkippedRatherThanStartingBeforeIt() {
        String filter = FfmpegClipProcessor.soundtrackFilter(List.of(
                new FilmAudioTrack("https://minio/tick.wav", 0, -4.0, 0, 500, 0.3)));

        assertThat(filter).doesNotContain("afade");
    }
}
