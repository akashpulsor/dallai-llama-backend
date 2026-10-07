package com.dalai.llama.preprod.dto.music;

import java.util.List;

/**
 * The score plan as the client sees it on the review page: what the music is and how it moves
 * through the film. Only the musical decisions -- never the generation prompt, the model or
 * anything else that is the creator's working material.
 */
public record PublicMusicPlanView(Identity identity, List<Section> sections, String ending, Double totalDurationSeconds) {

    public record Identity(String genre, String subGenre, String overallTone, Integer bpm, String keyOrScale,
                           String raga, String taal, String motif, String motifDescription,
                           List<String> coreInstruments, String culturalInfluence) {}

    public record Section(Double startTime, Double endTime, String storyBeat, String mood, Double energy,
                          List<String> activeInstruments) {}

    public static PublicMusicPlanView from(MusicPlan plan) {
        GlobalMusicIdentity g = plan.globalIdentity();
        Identity identity = g == null ? null : new Identity(g.genre(), g.subGenre(), g.overallTone(), g.bpm(),
                g.keyOrScale(), g.raga(), g.taal(), g.motif(), g.motifDescription(), g.coreInstruments(),
                g.culturalInfluence());
        List<Section> sections = plan.sections() == null ? List.of() : plan.sections().stream()
                .map(s -> new Section(s.startTime(), s.endTime(), s.storyBeat(), s.mood(), s.energy(), s.activeInstruments()))
                .toList();
        return new PublicMusicPlanView(identity, sections, plan.endingStrategy(), plan.totalDurationSeconds());
    }
}
