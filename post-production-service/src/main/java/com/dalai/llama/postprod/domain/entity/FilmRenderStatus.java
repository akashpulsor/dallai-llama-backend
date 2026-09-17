package com.dalai.llama.postprod.domain.entity;

/** Where an assembly of the whole film has got to. */
public enum FilmRenderStatus {

    /** Accepted and waiting its turn. Nothing is running yet. */
    QUEUED,

    /** ffmpeg is joining the shots. */
    PROCESSING,

    COMPLETED,

    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
