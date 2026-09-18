package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor.ConcatInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the film can be stapled together instead of re-encoded.
 *
 * <p>Getting this wrong in the permissive direction is the expensive mistake: {@code -c copy} across
 * streams that do not actually match produces a file that plays for a couple of seconds and stops,
 * and it would be uploaded and promoted looking exactly like a good one. So every case here is a
 * reason to REFUSE the fast path, except the first.
 *
 * <p>Same resolution is deliberately not enough on its own. Clips come from different models, and
 * two 1080x1920 clips can still disagree about frame rate, codec, pixel format or audio.
 */
class ConcatCopyDecisionTest {

    private static final int W = 1080;
    private static final int H = 1920;

    private static ConcatInput uniform() {
        return new ConcatInput("h264", "yuv420p", "24/1", W, H, "aac", "48000", "2");
    }

    @Test
    void copiesWhenEveryInputAlreadyAgrees() {
        assertTrue(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), uniform(), uniform()), W, H));
    }

    @Test
    void refusesOnDifferentFrameRate() {
        ConcatInput thirty = new ConcatInput("h264", "yuv420p", "30/1", W, H, "aac", "48000", "2");
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), thirty), W, H),
                "24fps and 30fps cannot be copied together even at the same resolution");
    }

    @Test
    void refusesOnDifferentCodec() {
        ConcatInput vp9 = new ConcatInput("vp9", "yuv420p", "24/1", W, H, "aac", "48000", "2");
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), vp9), W, H));
    }

    @Test
    void refusesOnDifferentPixelFormat() {
        ConcatInput yuv444 = new ConcatInput("h264", "yuv444p", "24/1", W, H, "aac", "48000", "2");
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), yuv444), W, H));
    }

    /** A silent shot among thirteen with sound. The concat demuxer does not fill the gap. */
    @Test
    void refusesWhenOneShotHasNoAudio() {
        ConcatInput silent = new ConcatInput("h264", "yuv420p", "24/1", W, H, null, null, null);
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), silent), W, H));
    }

    @Test
    void refusesOnDifferentAudioSampleRate() {
        ConcatInput at44k = new ConcatInput("h264", "yuv420p", "24/1", W, H, "aac", "44100", "2");
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), at44k), W, H));
    }

    /** Uniform inputs that are simply the wrong size: a copy join cannot resize, so this must encode. */
    @Test
    void refusesWhenUniformButNotTheFilmsSize() {
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(uniform(), uniform()), 1920, 1080),
                "a copy join cannot scale to the project's aspect ratio");
    }

    @Test
    void refusesWhenAnInputCouldNotBeProbed() {
        ConcatInput unreadable = new ConcatInput(null, null, null, 0, 0, null, null, null);
        assertFalse(FfmpegClipProcessor.canCopyJoin(List.of(unreadable, uniform()), W, H));
    }
}
