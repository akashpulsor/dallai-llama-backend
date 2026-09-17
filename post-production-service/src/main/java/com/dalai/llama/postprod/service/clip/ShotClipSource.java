package com.dalai.llama.postprod.service.clip;

import java.util.UUID;

/**
 * A shot's current clip and the take that belongs on it, as video-generation-service reports them.
 *
 * <p>Structural copy of that service's own {@code ShotClipSourceView}. The two services share no
 * module, so this is a deliberate duplicate rather than an import -- and the field names must match
 * the wire exactly, because a rename on one side and not the other fails silently as a null.
 */
public record ShotClipSource(UUID jobId,
                             String clipUrl,
                             Double clipSeconds,
                             String dubbedAudioUrl,
                             Double dubbedAudioSeconds,
                             String dubbedText,
                             String outputOrigin) {

    public boolean hasDub() {
        return dubbedAudioUrl != null && !dubbedAudioUrl.isBlank();
    }
}
