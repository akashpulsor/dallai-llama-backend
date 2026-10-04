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

    /** ElevenLabs Music refuses a prompt longer than this; the composed prompt always fits it. */
    public static final int MAX_PROMPT_CHARS = 4100;

    /** One identity field never takes more than this, so the opening stays a fraction of the budget. */
    private static final int IDENTITY_FIELD_CHARS = 220;

    /**
     * The full prompt when it fits. When it does not, the sections are told more briefly (mood,
     * arrangement and motif only), and failing that each section is cut to an equal share -- the
     * identity and the ending are never the part that is lost.
     */
    public static String compose(MusicPlan plan, List<MusicSection> orderedSections, double totalSeconds) {
        String opening = opening(plan.globalIdentity(), totalSeconds);
        String ending = ending(plan, totalSeconds);
        int budget = MAX_PROMPT_CHARS - opening.length() - ending.length();

        String sections = sections(orderedSections, false, Integer.MAX_VALUE);
        if (sections.length() > budget) {
            sections = sections(orderedSections, true, Integer.MAX_VALUE);
        }
        if (sections.length() > budget) {
            sections = sections(orderedSections, true, Math.max(40, budget / Math.max(1, orderedSections.size()) - 1));
        }
        return clip(opening + sections + ending, MAX_PROMPT_CHARS);
    }

    private static String opening(GlobalMusicIdentity id, double totalSeconds) {
        StringBuilder out = new StringBuilder();
        out.append("Create a continuous ").append(seconds(totalSeconds)).append("-second instrumental ");
        if (notBlank(id.genre())) out.append(clip(id.genre())).append(' ');
        out.append("score");
        if (id.bpm() != null && id.bpm() > 0) out.append(" at approximately ").append(id.bpm()).append(" BPM");
        if (notBlank(id.keyOrScale())) out.append(" in ").append(clip(id.keyOrScale()));
        out.append(".\n");

        // The raga is what gives the score a melody of its own instead of a mood bed; it leads.
        if (notBlank(id.raga())) {
            out.append("Base the melody on ").append(clip(id.raga()));
            if (notBlank(id.ragaPhrase())) out.append(" (").append(clip(id.ragaPhrase())).append(')');
            out.append(", keeping its characteristic phrases and melodic movement throughout");
            if (notBlank(id.taal())) out.append(", over ").append(clip(id.taal()));
            out.append(".\n");
        }
        out.append('\n');

        out.append("Maintain one coherent musical identity throughout");
        String palette = joinAll(id.coreInstruments(), id.supportingInstruments());
        if (notBlank(palette)) out.append(", built around ").append(clip(palette));
        out.append('.');
        if (notBlank(id.overallTone())) out.append(" The overall tone is ").append(clip(id.overallTone())).append('.');
        if (notBlank(id.productionStyle())) out.append(" Production style: ").append(clip(id.productionStyle())).append('.');
        out.append('\n');

        if (notBlank(id.motif())) {
            out.append("Use a recognisable ").append(clip(id.motif())).append(" as the through-line");
            if (notBlank(id.motifDescription())) out.append(" -- ").append(clip(id.motifDescription()));
            out.append(". The motif must stay the same piece of music throughout; sections may change how it is treated, never replace it.\n");
        }
        out.append('\n');
        return out.toString();
    }

    /** One line per section. Brief drops harmony and dialogue detail; lineLimit cuts every line to
     * the same length when even the brief form is too long. */
    private static String sections(List<MusicSection> orderedSections, boolean brief, int lineLimit) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < orderedSections.size(); i++) {
            MusicSection s = orderedSections.get(i);
            StringBuilder line = new StringBuilder();
            line.append(seconds(s.startTime())).append('-').append(seconds(s.endTime())).append(" seconds: ");
            if (i > 0) {
                // Stated on every section after the first, and first on the line so a cut never
                // loses it -- ignoring it is what produces stitched-together cues.
                line.append(brief || !notBlank(s.transitionFromPrevious()) ? "Continue the same composition" : s.transitionFromPrevious())
                    .append(". Do not restart the music. ");
            }
            if (notBlank(s.mood())) line.append(s.mood()).append(". ");
            if (notBlank(s.arrangement())) line.append(brief ? clip(s.arrangement(), 140) : s.arrangement()).append(". ");
            if (notBlank(s.motifTreatment())) line.append(brief ? clip(s.motifTreatment(), 100) : s.motifTreatment()).append(". ");
            if (!brief && notBlank(s.harmonyTreatment())) line.append(s.harmonyTreatment()).append(". ");
            if (!brief && notBlank(s.dialogueTreatment())) line.append(s.dialogueTreatment()).append(". ");
            out.append(clip(line.toString().trim(), lineLimit)).append('\n');
        }
        return out.toString();
    }

    private static String ending(MusicPlan plan, double totalSeconds) {
        StringBuilder out = new StringBuilder();
        out.append('\n').append("The entire piece must sound like one continuously composed score, not separate tracks stitched together. ")
           .append("Instrumental only, no vocals.\n");
        if (notBlank(plan.endingStrategy())) {
            out.append(clip(plan.endingStrategy())).append(' ');
        }
        out.append("Finish with an intentional, resolved ending exactly at ")
           .append(seconds(totalSeconds)).append(" seconds.");
        return out.toString();
    }

    private static String clip(String value) {
        return clip(value, IDENTITY_FIELD_CHARS);
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1).trim() + "…";
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
