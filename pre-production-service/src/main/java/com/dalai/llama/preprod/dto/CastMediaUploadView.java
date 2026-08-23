package com.dalai.llama.preprod.dto;

/** What a cast-media upload hands back -- the caller feeds {@code bucket}/{@code objectKey}
 * straight into {@code CreateCastProfileRequest}'s face/voice ref fields. */
public record CastMediaUploadView(
        String bucket,
        String objectKey
) {
}
