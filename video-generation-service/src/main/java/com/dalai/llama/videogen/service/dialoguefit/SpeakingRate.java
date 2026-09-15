package com.dalai.llama.videogen.service.dialoguefit;

import java.util.List;

/**
 * How fast this voice actually speaks, derived from audio we have already measured.
 *
 * <p>Replaces asking a language model how long a line takes to say, which does not work. The
 * {@code VIDEO_DIALOGUE_FIT} prompt asked exactly that about shot-01-003 and answered "about
 * eighteen" seconds; the synthesized take runs 4.32. Sizing a rewrite from a judgement that is out
 * by a factor of four produces a rewrite that misses by the same factor.
 *
 * <p>It also replaces a fixed characters-per-second constant, for a smaller but more insidious
 * reason. Measured across the eight takes in the live project, the real rate is 13.2 chars/sec while
 * the configured default is 14 -- and because the default is too FAST, every estimate it produces is
 * too SHORT. Estimates then understate overruns, which is the one direction an error must not go:
 * the check exists to catch lines that will be cut, and a rate that flatters them hides exactly the
 * shots it was built to find. Errors reached -16% on the real lines.
 *
 * <h2>Three sources, in order of trust</h2>
 * <ol>
 *   <li>{@link Source#THIS_LINE} -- the line's own synthesized take. Same voice, same language, same
 *       density of characters, so nothing has to be generalised at all.</li>
 *   <li>{@link Source#PROJECT} -- the mean across every take in the project. One voice and one
 *       language in practice, which is why it holds up: 8.5% deviation across the real takes.</li>
 *   <li>{@link Source#CONFIGURED} -- the property. A guess, and labelled as one.</li>
 * </ol>
 *
 * <h2>Why characters, having tried the alternatives</h2>
 * Counting units were compared against the real takes. Characters gave 8.5% deviation, graphemes
 * (dropping Devanagari vowel signs) 8.1%, and a hand-written syllable counter 10.8% -- worse than
 * either, for considerably more code and a rule per script.
 *
 * <p>More to the point, the unit cancels out of the calculation that matters. A rewrite is asked for
 * as a RATIO of the line's own measured length, and shot-01-003's target of 2.6s from 4.32s is 60%
 * of the original whether it is counted in characters, graphemes or syllables. The unit only affects
 * the secondary character budget, so the simplest one that works is the right one.
 */
public record SpeakingRate(double secondsPerChar, Source source) {

    public enum Source {
        /** Measured from this very line's own synthesized take. */
        THIS_LINE,
        /** Averaged over every measured take in the project. */
        PROJECT,
        /** The configured fallback -- nothing in this project has been synthesized yet. */
        CONFIGURED;

        /** False only for {@link #CONFIGURED}: anything derived from real audio is a measurement of
         * this voice, and anything else is a guess that must be shown as one. */
        public boolean fromAudio() {
            return this != CONFIGURED;
        }
    }

    /** One measured take: the text that was spoken and how long speaking it took. */
    public record Take(int characters, double seconds) {

        public boolean usable() {
            return characters > 0 && seconds > 0;
        }
    }

    public static SpeakingRate fromConfiguredCharsPerSecond(double charsPerSecond) {
        double rate = charsPerSecond > 0 ? charsPerSecond : 14;
        return new SpeakingRate(1.0 / rate, Source.CONFIGURED);
    }

    /** The rate this exact line was spoken at. The best available, because nothing is generalised. */
    public static SpeakingRate fromLine(int characters, double seconds) {
        return new SpeakingRate(seconds / characters, Source.THIS_LINE);
    }

    /**
     * The project's own rate, averaged over its takes.
     *
     * <p>Averaged as seconds-per-character rather than as characters-per-second: the two disagree
     * (the mean of reciprocals is not the reciprocal of the mean), and seconds-per-character is the
     * direction every caller actually uses -- turning text into a duration, and a duration into a
     * budget. Averaging the convenient way and inverting would bias every estimate slightly short,
     * which is the direction that hides overruns.
     *
     * <p>Returns the configured fallback when nothing has been measured yet.
     */
    public static SpeakingRate fromProject(List<Take> takes, double configuredCharsPerSecond) {
        List<Take> usable = takes == null ? List.<Take>of() : takes.stream().filter(Take::usable).toList();
        if (usable.isEmpty()) {
            return fromConfiguredCharsPerSecond(configuredCharsPerSecond);
        }
        double total = usable.stream().mapToDouble(t -> t.seconds() / t.characters()).sum();
        return new SpeakingRate(total / usable.size(), Source.PROJECT);
    }

    /** How long this text should take to say at this rate. */
    public double secondsFor(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().length() * secondsPerChar;
    }

    /**
     * Roughly how many characters fit in {@code targetSeconds}.
     *
     * <p>Handed to the rewrite prompt as a concrete anchor beside the ratio, because "about 41
     * characters" is something a model can aim at and we can check, whereas "2.6 seconds" is a unit
     * it has already proven it cannot judge. Advisory, never a hard limit -- a line two characters
     * over is not a failed rewrite.
     */
    public int charBudgetFor(double targetSeconds) {
        return secondsPerChar <= 0 ? 0 : (int) Math.round(targetSeconds / secondsPerChar);
    }

    public double charsPerSecond() {
        return secondsPerChar <= 0 ? 0 : 1.0 / secondsPerChar;
    }
}
