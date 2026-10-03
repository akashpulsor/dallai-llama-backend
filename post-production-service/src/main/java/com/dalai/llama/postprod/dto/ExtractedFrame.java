package com.dalai.llama.postprod.dto;


import java.nio.file.Path;

public record ExtractedFrame(
        long frameNumber,
        long timestampMs,
        Path file
) {
}