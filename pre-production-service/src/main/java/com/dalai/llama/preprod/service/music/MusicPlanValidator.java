package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;
import com.dalai.llama.preprod.service.PreProductionException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Checks a plan is a usable timeline before anything is generated from it.
 *
 * <p>Only structural facts are checked -- starts at zero, no gaps, no overlaps, ends exactly at
 * the film's length. Whether a section boundary is musically justified is a judgement this cannot
 * make from numbers, so it is asked for in the prompt and asserted in tests instead of being
 * pretended at here.
 *
 * <p>Tolerance exists because the plan arrives from a language model and section times come back
 * as decimals. A millisecond of float drift is not a gap worth failing a paid generation over;
 * anything larger is a real hole in the score.
 */
public final class MusicPlanValidator {

    /** 50ms. Below audible, above float noise. */
    private static final double TOLERANCE_SECONDS = 0.05;

    private MusicPlanValidator() {}

    /** @return the sections in timeline order, so callers do not re-sort. */
    public static List<MusicSection> validate(MusicPlan plan, double expectedTotalSeconds) {
        if (plan == null || plan.sections() == null || plan.sections().isEmpty()) {
            throw PreProductionException.upstream("Music plan has no sections");
        }
        if (plan.globalIdentity() == null) {
            throw PreProductionException.upstream("Music plan has no global musical identity");
        }
        List<MusicSection> ordered = new ArrayList<>(plan.sections());
        ordered.sort(Comparator.comparingDouble(s -> s.startTime() == null ? 0 : s.startTime()));

        for (MusicSection section : ordered) {
            if (section.startTime() == null || section.endTime() == null) {
                throw PreProductionException.upstream("Music section is missing a start or end time");
            }
            if (section.endTime() <= section.startTime()) {
                throw PreProductionException.upstream(
                        "Music section ends before it starts: %.2f -> %.2f".formatted(section.startTime(), section.endTime()));
            }
        }
        if (Math.abs(ordered.get(0).startTime()) > TOLERANCE_SECONDS) {
            throw PreProductionException.upstream(
                    "Music must start at 0s, plan starts at %.2fs".formatted(ordered.get(0).startTime()));
        }
        for (int i = 1; i < ordered.size(); i++) {
            double previousEnd = ordered.get(i - 1).endTime();
            double thisStart = ordered.get(i).startTime();
            double delta = thisStart - previousEnd;
            if (Math.abs(delta) > TOLERANCE_SECONDS) {
                throw PreProductionException.upstream(delta > 0
                        ? "Gap in the score between %.2fs and %.2fs -- the music would stop".formatted(previousEnd, thisStart)
                        : "Music sections overlap between %.2fs and %.2fs".formatted(thisStart, previousEnd));
            }
        }
        double planEnd = ordered.get(ordered.size() - 1).endTime();
        if (Math.abs(planEnd - expectedTotalSeconds) > TOLERANCE_SECONDS) {
            throw PreProductionException.upstream(
                    "Music ends at %.2fs but the video is %.2fs long".formatted(planEnd, expectedTotalSeconds));
        }
        return ordered;
    }
}
