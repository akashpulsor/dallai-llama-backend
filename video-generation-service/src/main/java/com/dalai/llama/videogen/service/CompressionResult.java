package com.dalai.llama.videogen.service;

public record CompressionResult(
        String compressedPrompt,
        boolean compressionApplied,
        int originalLength,
        int compressedLength,
        boolean namedEntitiesValidated
) {
}
