package com.dalai.llama.videogen.dto.generationplan;

import java.util.List;

/**
 * What the shot's video model can be asked for. {@code declared} is false for a model with no
 * capability row, whose durations fall back to the service's configured bounds and whose frame
 * rate is unknown -- {@code supportedFps} is then empty, which means "not known", not "anything".
 */
public record VideoModelCapabilitiesView(
        String modelId,
        List<Integer> supportedDurationsSeconds,
        List<Integer> supportedFps,
        boolean declared
) {
}
