package com.dalai.llama.postprod.dto;

public record ShotFrameCommand(
        long frameNumber,
        long timestampMs,
        String bucket,
        String objectKey
) {
}