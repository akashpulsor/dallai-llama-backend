package com.dalai.llama.videogen.dto.generationplan;

/**
 * A shot's effective generation controls. {@code custom} is whether the shot has its own set;
 * false means it follows the project's defaults, which {@code controls} then are.
 */
public record ShotGenerationControlsView(
        GenerationControlsView controls,
        boolean custom
) {
}
