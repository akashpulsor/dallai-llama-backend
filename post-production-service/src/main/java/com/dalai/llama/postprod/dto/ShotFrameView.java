package com.dalai.llama.postprod.dto;

import java.util.UUID;

/**
 * One extracted frame. {@code bucket}/{@code objectKey} are for another service to attach the frame
 * as a reference; {@code imageUrl} is a short-lived signed link for a browser.
 * {@code frameNumber} is the frame's index in its clip from 0, null when the clip's rate is unknown.
 */
public record ShotFrameView(
        UUID frameId,
        UUID shotId,
        UUID clipVersionId,
        String mode,
        Long frameNumber,
        Long timestampMs,
        String bucket,
        String objectKey,
        String imageUrl
) {
}
