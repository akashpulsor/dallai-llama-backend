package com.dalai.llama.postprod.service.clip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The picture side of conforming a clip to its planned length. */
class ConformFilterTest {

    @Test
    void aShorterClipIsSlowedAndInterpolatedBackToItsOwnFrameRate() {
        // 3s generated, 6s planned: twice as slow, new frames synthesised so it still plays at 30fps.
        assertThat(FfmpegClipProcessor.conformVideoFilter(3.0, 6.0, true, 30))
                .isEqualTo("setpts=2.000000*PTS,minterpolate=fps=30.000:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1");
    }

    @Test
    void withoutInterpolationTheSlowedClipRepeatsFramesInstead() {
        assertThat(FfmpegClipProcessor.conformVideoFilter(3.0, 6.0, false, 24)).isEqualTo("setpts=2.000000*PTS,fps=24.000");
    }

    @Test
    void aLongerClipIsTrimmedNeverSpedUp() {
        // A model's 2s minimum for a 1s shot.
        assertThat(FfmpegClipProcessor.conformVideoFilter(2.0, 1.0, true, 30)).isEqualTo("trim=duration=1.000,setpts=PTS-STARTPTS");
    }

    @Test
    void aClipAlreadyAtLengthIsOnlyReencoded() {
        assertThat(FfmpegClipProcessor.conformVideoFilter(6.0, 6.0, true, 30)).isEqualTo("fps=30.000");
        // An unknown frame rate falls back to 30 rather than producing an invalid filter.
        assertThat(FfmpegClipProcessor.conformVideoFilter(6.0, 6.0, true, 0)).isEqualTo("fps=30.000");
    }
}
