package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.EmotionalArcPosition;

/** Simple ordinal-position heuristic standing in for the full EMOTIONAL_ARC_BEAT system (deferred
 * past this v1 slice, see the design doc's §9.7) -- still produces a valid, non-null
 * {@code Narrative.arcPosition} for every dispatched shot. */
public final class EmotionalArcPositionCalculator {

    private EmotionalArcPositionCalculator() {
    }

    public static EmotionalArcPosition fromOrdinal(int zeroBasedIndex, int totalShots) {
        if (totalShots <= 1) {
            return EmotionalArcPosition.CLIMAX;
        }
        double position = (double) zeroBasedIndex / (totalShots - 1);
        if (position < 0.15) {
            return EmotionalArcPosition.SETUP;
        }
        if (position < 0.7) {
            return EmotionalArcPosition.RISING;
        }
        if (position < 0.9) {
            return EmotionalArcPosition.CLIMAX;
        }
        return EmotionalArcPosition.RESOLUTION;
    }
}
