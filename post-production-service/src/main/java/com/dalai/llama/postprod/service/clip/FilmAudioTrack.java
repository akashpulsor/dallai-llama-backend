package com.dalai.llama.postprod.service.clip;

/** One sound mixed under the joined film: where it is read from, when it starts in the film, how
 * loud, and its fades. {@code durationSeconds} is needed only to place a fade-out; null skips it. */
public record FilmAudioTrack(String source, long startMs, double volumeDb, long fadeInMs, long fadeOutMs,
                             Double durationSeconds) {
}
