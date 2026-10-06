package com.dalai.llama.postprod.domain;

/** What a sound layer is, which sets its default level and fades. */
public enum SoundLayerKind {
    /** A music cue for a moment in the film, under the dialogue and the project score. */
    MUSIC,
    /** A single sound -- a bell, a door, a crowd swell -- placed at a point in the film. */
    SOUND_EFFECT
}
