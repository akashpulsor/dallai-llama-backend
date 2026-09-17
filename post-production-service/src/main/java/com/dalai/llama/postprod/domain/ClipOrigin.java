package com.dalai.llama.postprod.domain;

/** What was done to the picture to make this cut of a shot. */
public enum ClipOrigin {

    /** The clip as the video model produced it, carrying whatever audio it invented. */
    GENERATED,

    /** The same picture with the recorded take in place of the clip's own audio.
     *
     * <p>Replaced, not mixed: a line the model half-spoke underneath the line it should have spoken
     * is worse than either alone. */
    DUBBED,

    /** The same picture with no voice at all -- silence, not a missing track, because the film is
     * assembled with a concat filter that demands an audio stream on every input. */
    SILENT,

    /** A file the creator cut themselves and brought back. The escape hatch that stops any of this
     * being a dead end when no automatic cut is worth shipping. */
    UPLOADED
}
