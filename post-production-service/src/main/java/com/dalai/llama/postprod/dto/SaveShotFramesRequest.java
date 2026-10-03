package com.dalai.llama.postprod.dto;

public record SaveShotFramesRequest(
        List<ShotFrameCommand> frames
) {
}