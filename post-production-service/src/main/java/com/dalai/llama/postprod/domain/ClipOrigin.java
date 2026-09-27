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

    /** The same picture slowed to fill a longer slot than it was generated for.
     *
     * <p>Exists to let a shot be generated short -- and paid for short -- then stretched to the
     * length the plan actually wants. The audio is dropped rather than stretched: time-stretching
     * speech is what makes a retimed shot sound wrong, and the dubbed cut already puts the cloned
     * line back at normal speed. So the chain is GENERATED (short) -> RETIMED (right length, mute)
     * -> DUBBED (line restored), each a version the creator can play before accepting. */
    RETIMED,

    /** A file the creator cut themselves and brought back. The escape hatch that stops any of this
     * being a dead end when no automatic cut is worth shipping. */
    UPLOADED
}
