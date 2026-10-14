package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.service.generationplan.VideoModelCapabilityService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How many seconds to ask the video model for: the length the creator chose, brought inside what
 * the model accepts. Never refuses, and never lengthens a clip to hold a line -- a line too long for
 * its shot is fixed by extending the shot or rephrasing the line, before anything is generated.
 *
 * <p>A request below the model's minimum or above its maximum is clamped, because asking anyway is
 * a guaranteed failure (fal.ai answers a 1s Wan request with a 422).
 */
@Component
public class RenderDurationPolicy {

    private final VideoModelCapabilityService capabilities;

    public RenderDurationPolicy(VideoModelCapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    /** {@code seconds} brought inside what {@code modelId} accepts; its minimum when unset. */
    public int clamp(String modelId, Integer seconds) {
        List<Integer> supported = capabilities.capabilities(modelId).supportedDurationsSeconds();
        int min = supported.get(0);
        int max = supported.get(supported.size() - 1);
        return seconds == null ? min : Math.max(min, Math.min(max, seconds));
    }
}
