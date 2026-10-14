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
                             String outputOrigin,
                             /** Every take the clip carries, each at its line's planned start -- one per
                              * line in a multi-line shot. Null from a video-generation that predates it. */
                             java.util.List<PlacedTake> takes) {

    /** One recorded take and the second of the shot it starts at. */
    public record PlacedTake(String audioUrl, double startSeconds) {
    }

    /** The takes to lay on: the placed ones, else the single newest take from the first frame. */
    public java.util.List<PlacedTake> placedTakes() {
        if (takes != null && !takes.isEmpty()) return takes;
        return dubbedAudioUrl == null || dubbedAudioUrl.isBlank() ? java.util.List.of()
                : java.util.List.of(new PlacedTake(dubbedAudioUrl, 0));
    }

    public boolean hasDub() {
        return !placedTakes().isEmpty();
    }
}
