package com.dalai.llama.preprod.dto.music;

import java.util.List;

/**
 * One stretch of the score, bounded by a story or emotional change -- never by a shot change.
 *
 * <p>That distinction is the whole point of the type. Six shots inside one emotional beat are one
 * section; a single long shot that turns from tension to relief is two. A planner that emits one
 * section per shot has misunderstood the job, and {@code MusicPlanValidator} does not police that
 * (it cannot tell intent from timing) -- the prompt asks for it and the tests assert it.
 *
 * <p>Times are seconds from the start of the film, not offsets within the section, so a plan reads
 * as a timeline. Sections are contiguous: {@code endTime} of one is {@code startTime} of the next.
 */
public record MusicSection(
        Double startTime,
        Double endTime,
        String storyBeat,
        String mood,
        /** 0..1, expected to sit inside the identity's overallEnergyRange. */
        Double energy,
        /** 0..1. */
        Double tension,
        String arrangement,
        List<String> activeInstruments,
        /** How the global motif appears here -- "introduce softly", "same motif, harmonically
         * tense", "state it clearly". Never a different motif. */
        String motifTreatment,
        String harmonyTreatment,
        String rhythmTreatment,
        /** How this continues the previous section. Should describe evolution, not a restart --
         * "continue seamlessly", not "new theme begins". */
        String transitionFromPrevious,
        /** What the arrangement does underneath speech here. Density instruction only: actual
         * level automation is the mixer's job, not the composer's. */
        String dialogueTreatment,
        /** Moments inside this section the music should land on, in seconds from film start. */
        List<Double> importantSyncPoints
) {
    public double durationSeconds() {
        return (endTime == null ? 0 : endTime) - (startTime == null ? 0 : startTime);
    }
}
