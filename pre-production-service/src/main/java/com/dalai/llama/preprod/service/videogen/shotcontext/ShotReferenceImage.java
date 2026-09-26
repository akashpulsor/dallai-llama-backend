package com.dalai.llama.preprod.service.videogen.shotcontext;

/** Outbound mirror of video-gen's ShotContext.ShotReferenceImage nested record. One
 * creator-uploaded reference image on a shot -- assembled from V66 shot_reference_image rows.
 * caption is optional (feeds prompt-text captioning as a follow-up); ordinal preserves the
 * creator's chosen upload order so the video model receives them in that same order. */
public record ShotReferenceImage(
        String bucket,
        String objectKey,
        String contentType,
        String caption,
        Integer ordinal
) {
}
