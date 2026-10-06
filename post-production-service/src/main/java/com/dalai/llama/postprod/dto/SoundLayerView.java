package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import com.dalai.llama.postprod.domain.SoundLayerSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A sound layer as the page shows it, with a short-lived link to listen to it. */
public record SoundLayerView(UUID layerId, UUID shotId, SoundLayerKind kind, SoundLayerSource source, String prompt,
                             String audioUrl, BigDecimal durationSeconds, int offsetMs, BigDecimal volumeDb,
                             int fadeInMs, int fadeOutMs, boolean included, OffsetDateTime createdAt) {
}
