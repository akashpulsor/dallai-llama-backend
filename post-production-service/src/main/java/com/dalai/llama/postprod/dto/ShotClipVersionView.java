package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.entity.ShotClipVersion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cut of a shot, as the page sees it.
 *
 * <p>Carries a playable URL rather than a bucket and key: a preview exists to be watched before it
 * is chosen, and a caller that has to ask a second endpoint how to play it will not bother.
 *
 * @param status PREVIEW / ACTIVE / SUPERSEDED. ACTIVE is the cut the film uses; there is exactly one
 *               per shot.
 * @param origin GENERATED / DUBBED / SILENT / UPLOADED -- what was done to the picture.
 */
public record ShotClipVersionView(
        UUID versionId,
        UUID shotId,
        String shotRef,
        int versionNumber,
        String origin,
        String status,
        String videoUrl,
        BigDecimal durationSeconds,
        Integer width,
        Integer height,
        Boolean hasAudio,
        OffsetDateTime createdAt,
        OffsetDateTime acceptedAt
) {

    public static ShotClipVersionView of(ShotClipVersion version, String videoUrl) {
        return new ShotClipVersionView(
                version.getVersionId(),
                version.getShotId(),
                version.getShotRef(),
                version.getVersionNumber(),
                version.getOrigin().name(),
                version.getStatus().name(),
                videoUrl,
                version.getDurationSeconds(),
                version.getWidth(),
                version.getHeight(),
                version.getHasAudio(),
                version.getCreatedAt(),
                version.getAcceptedAt());
    }
}
