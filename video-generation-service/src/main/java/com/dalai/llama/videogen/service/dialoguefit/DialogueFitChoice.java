package com.dalai.llama.videogen.service.dialoguefit;

/**
 * What to do about a shot whose line does not fit the clip it is planned for, chosen by the creator
 * before generating.
 *
 * <p>Only two values, because only two of the three offered remedies reach dispatch. Rewriting the
 * dialogue is the third, and it happens earlier and elsewhere: it changes the shot's text, so by the
 * time the shot is approved there is nothing left to decide -- the line simply fits. This enum is
 * for the choice that is still open at the moment the money is spent.
 *
 * <p>Sent per approval rather than stored on the shot. The answer belongs to one render of one
 * version of a line: change the words, or the shot's length, and the question is a different one. A
 * column holding the last answer would quietly apply it to a shot it was never given for.
 */
public enum DialogueFitChoice {

    /**
     * Give the shot the seconds its line needs, up to the longest clip the model will generate.
     *
     * <p>The default, because the alternative is a line cut off mid-word -- and a clip running a
     * second or two longer than planned is a far smaller problem than a deliverable with severed
     * dialogue in it. It does cost more: clips are billed by the second.
     */
    EXTEND,

    /**
     * Generate at the length the shot was planned for, and accept what that does to the audio.
     *
     * <p>"Go with the original" -- the creator has seen the numbers and wants the shot as written and
     * timed. The line will be hurried to fit where that is possible, and cut where it is not. A
     * legitimate choice when the cut falls somewhere harmless, or when the shot's length is fixed by
     * an edit that matters more than the tail of the line, but never a silent default: nothing picks
     * this on a creator's behalf.
     */
    KEEP_PLANNED;

    public static DialogueFitChoice parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXTEND;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // An unrecognised choice is not a reason to refuse a render, and defaulting to EXTEND
            // errs towards the outcome that keeps the whole line audible.
            return EXTEND;
        }
    }
}
