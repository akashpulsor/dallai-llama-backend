package com.dalai.llama.postprod.service.clip;

import java.math.BigDecimal;

/**
 * What a file actually is, measured rather than assumed.
 *
 * <p>Every cut is probed before it is stored. A cut with no picture in it, or no length, used to be
 * uploaded and promoted exactly like a good one -- which is how a finished shot came back with no
 * video at all. Measuring is the cheap half of never doing that again.
 *
 * @param hasVideo false for a file that is not a video at all, whatever its extension claims.
 * @param hasAudio false for a picture with no track. The film's concat filter demands one on every
 *                 input, so this is checked before a cut is allowed to become the shot's clip.
 */
public record ClipProbe(boolean hasVideo, boolean hasAudio, BigDecimal durationSeconds,
                        Integer width, Integer height) {

    /** Enough of a video to be worth storing: a picture, and some length to it. */
    public boolean isPlayable() {
        return hasVideo && durationSeconds != null && durationSeconds.signum() > 0;
    }
}
