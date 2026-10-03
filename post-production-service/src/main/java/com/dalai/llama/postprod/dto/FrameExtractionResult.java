package com.dalai.llama.postprod.dto;


import java.util.List;

public record FrameExtractionResult(
        int frameCount,
        List<ExtractedFrame> frames
) {
}