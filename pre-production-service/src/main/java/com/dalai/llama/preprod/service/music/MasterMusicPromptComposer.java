package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.GlobalMusicIdentity;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;

import java.util.List;
import java.util.Locale;

/**
 * Turns a plan into the one instruction a music model is given.
 *
 * <p>Deterministic on purpose. The planner decides the music; this only renders that decision as
 * prose, so a creator can edit a section and get a prompt that matches without paying for another
 * planning call. It is also why {@code masterPrompt} is stored rather than treated as the plan --
 * it can always be rebuilt from the structured fields.
 *
 * <p>The wording exists to fight one specific failure: music models asked for several moods tend
 * to return several pieces. Every section is therefore phrased as a continuation of the same
 * composition, the identity is stated once up front rather than restated per section, and the
 * close is described as a resolution at the exact duration instead of leaving the model to run
 * long and be cut.
 */
public final class MasterMusicPromptComposer {

    private MasterMusicPromptComposer() {}

    public static String compose(MusicPlan plan, List<MusicSection> orderedSections, double totalSeconds) {
        GlobalMusicIdentity id = plan.globalIdentity();
        StringBuilder out = new StringBuilder();

        out.append("Create a continuous ").append(seconds(totalSeconds)).append("-second instrumental ");
        if (notBlank(id.genre())) out.append(id.genre()).append(' ');
        out.append("score");
        if (id.bpm() != null && id.bpm() > 0) out.append(" at approximately ").append(id.bpm()).append(" BPM");
        if (notBlank(id.keyOrScale())) out.append(" in ").append(id.keyOrScale());
        out.append(".\n\n");

        out.append("Maintain one coherent musical identity throughout");
        String palette = joinAll(id.coreInstruments(), id.supportingInstruments());
        if (notBlank(palette)) out.append(", built around ").append(palette);
        out.append('.');
        if (notBlank(id.overallTone())) out.append(" The overall tone is ").append(id.overallTone()).append('.');
        if (notBlank(id.productionStyle())) out.append(" Production style: ").append(id.productionStyle()).append('.');
        out.append('\n');

        if (notBlank(id.motif())) {
            out.append("Use a recognisable ").append(id.motif()).append(" as the through-line");
            if (notBlank(id.motifDescription())) out.append(" -- ").append(id.motifDescription());
            out.append(". The motif must stay the same piece of music throughout; sections may change how it is treated, never replace it.\n");
        }
        out.append('\n');

        for (int i = 0; i < orderedSections.size(); i++) {
            MusicSection s = orderedSections.get(i);
            out.append(seconds(s.startTime())).append('-').append(seconds(s.endTime())).append(" seconds: ");
            if (notBlank(s.mood())) out.append(s.mood()).append(". ");
            if (notBlank(s.arrangement())) out.append(s.arrangement()).append(". ");
            if (notBlank(s.motifTreatment())) out.append(s.motifTreatment()).append(". ");
            if (notBlank(s.harmonyTreatment())) out.append(s.harmonyTreatment()).append(". ");
            if (i > 0) {
                // Stated on every section after the first, because this is the instruction models
                // most often ignore -- and ignoring it is what produces stitched-together cues.
                out.append(notBlank(s.transitionFromPrevious()) ? s.transitionFromPrevious() : "Continue the same composition")
                   .append(". Do not restart the music. ");
            }
            if (notBlank(s.dialogueTreatment())) out.append(s.dialogueTreatment()).append(". ");
            out.append('\n');
        }

        out.append('\n').append("The entire piece must sound like one continuously composed score, not separate tracks stitched together. ")
           .append("Instrumental only, no vocals.\n");
        if (notBlank(plan.endingStrategy())) {
            out.append(plan.endingStrategy()).append(' ');
        }
        out.append("Finish with an intentional, resolved ending exactly at ")
           .append(seconds(totalSeconds)).append(" seconds.");
        return out.toString();
    }

    private static String seconds(Double value) {
        double v = value == null ? 0 : value;
        // Whole seconds read as instructions; fractions only when the timeline actually has them.
        return v == Math.rint(v)
                ? String.valueOf((long) v)
                : String.format(Locale.ROOT, "%.1f", v);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @SafeVarargs
    private static String joinAll(List<String>... lists) {
        StringBuilder joined = new StringBuilder();
        for (List<String> list : lists) {
            if (list == null) continue;
            for (String item : list) {
                if (item == null || item.isBlank()) continue;
                if (joined.length() > 0) joined.append(", ");
                joined.append(item.trim());
            }
        }
        return joined.toString();
    }
}
