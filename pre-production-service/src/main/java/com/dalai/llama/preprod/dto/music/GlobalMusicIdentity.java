package com.dalai.llama.preprod.dto.music;

import java.util.List;

/**
 * The one musical DNA a video is scored with -- decided once, for the whole film.
 *
 * <p>Exists because the alternative is what this system did before:every shot asked a music model for
 * something on its own, so a thirty-second ad came back as six unrelated cues. Genre, motif, tempo
 * and palette live here precisely so they are NOT re-decided per section; a section may change how
 * the motif is treated, never what the motif is.
 *
 * <p>Provider-neutral by construction. Nothing here names ElevenLabs, fal.ai, a model id or a
 * request field -- translation to any of those belongs in the provider adapter.
 */
public record GlobalMusicIdentity(
        String genre,
        String subGenre,
        String overallTone,
        Integer bpm,
        String keyOrScale,
        String timeSignature,
        List<String> coreInstruments,
        List<String> supportingInstruments,
        String sonicTexture,
        /** The short recurring phrase the whole score is built from, named so sections can refer
         * to it ("rising three-note melody"). */
        String motif,
        String motifDescription,
        String rhythmicCharacter,
        String culturalInfluence,
        String productionStyle,
        EnergyRange overallEnergyRange
) {
    /** 0..1 at both ends. The floor and ceiling the arrangement moves between -- a section's own
     * energy is expected to sit inside this, which is what stops one beat blowing out the mix. */
    public record EnergyRange(Double min, Double max) {}
}
