package com.dalai.llama.postprod.service.clip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** ffprobe reports frame rates as fractions; an unreadable one must read as unknown, never as 0/0. */
class FrameRateParsingTest {

    @Test
    void fractionsAndPlainNumbersAreRead() {
        assertThat(FfmpegClipProcessor.parseFrameRate("30/1")).isEqualTo(30.0);
        assertThat(FfmpegClipProcessor.parseFrameRate("30000/1001")).isCloseTo(29.97, within(0.01));
        assertThat(FfmpegClipProcessor.parseFrameRate("24")).isEqualTo(24.0);
    }

    @Test
    void anythingUnreadableIsUnknown() {
        assertThat(FfmpegClipProcessor.parseFrameRate("0/0")).isZero();
        assertThat(FfmpegClipProcessor.parseFrameRate("")).isZero();
        assertThat(FfmpegClipProcessor.parseFrameRate("N/A")).isZero();
    }
}
