package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** Conforming a shot's newest clip to its planned length. */
public final class ShotConformDtos {

    private ShotConformDtos() {
    }

    /** {@code interpolate} null means yes: slowed-down motion without it judders. */
    /** {@code ai}: slow with Topaz, whose model generates the missing frames (paid), rather than ffmpeg. */
    public record Request(@NotNull @Positive BigDecimal targetSeconds, Boolean interpolate, Boolean ai) {
    }

    /** QUEUED or PROCESSING while the consumer works; COMPLETED with the cut made (active, or a
     * preview for the creator to choose), or FAILED with why. */
    public record View(UUID requestId, UUID shotId, BigDecimal targetSeconds, boolean interpolate, String status,
                       UUID sourceJobId, UUID versionId, String error) {
    }

    /** What the queue carries; the rest is on the shot_clip_conform row. */
    public record RequestedEvent(UUID requestId, UUID tenantId, UUID shotId) {
    }
}
