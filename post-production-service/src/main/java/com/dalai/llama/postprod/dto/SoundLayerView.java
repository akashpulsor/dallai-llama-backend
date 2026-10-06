package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.FrameExtractionStatus;
import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A sound layer as the page shows it, with a short-lived link to listen to it once it is ready
 * ({@code status} COMPLETED; while QUEUED or PROCESSING {@code audioUrl} is null, and a FAILED
 * layer carries its {@code error}). */
public record SoundLayerView(UUID layerId, UUID shotId, SoundLayerKind kind, SoundLayerSource source, String prompt,
                             FrameExtractionStatus status, String error,
                             String audioUrl, BigDecimal durationSeconds, int offsetMs, BigDecimal volumeDb,
                             int fadeInMs, int fadeOutMs, boolean included, OffsetDateTime createdAt) {
}
