package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.entity.VideoModelFrameRate;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.repository.VideoModelFrameRateRepository;
import com.dalai.llama.videogen.repository.VideoModelGenerationCapabilityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

/**
 * What a video model can be asked for (V39's capability tables).
 *
 * <p>A model with no row is not refused: its durations fall back to the service's configured
 * bounds -- the limits every shot was generated under before these tables existed -- and its frame
 * rate is reported as unknown rather than guessed. {@code declared} says which of the two the
 * caller is looking at.
 */
@Service
public class VideoModelCapabilityService {

    private final VideoModelGenerationCapabilityRepository capabilityRepository;
    private final VideoModelFrameRateRepository frameRateRepository;
    private final int fallbackMinSeconds;
    private final int fallbackMaxSeconds;

    public VideoModelCapabilityService(
            VideoModelGenerationCapabilityRepository capabilityRepository,
            VideoModelFrameRateRepository frameRateRepository,
            @Value("${video-gen.min-shot-duration-seconds:3}") int fallbackMinSeconds,
            @Value("${video-gen.max-shot-duration-seconds:10}") int fallbackMaxSeconds) {
        this.capabilityRepository = capabilityRepository;
        this.frameRateRepository = frameRateRepository;
        this.fallbackMinSeconds = fallbackMinSeconds;
        this.fallbackMaxSeconds = fallbackMaxSeconds;
    }

    public VideoModelCapabilitiesView capabilities(String modelId) {
        return capabilityRepository.findById(modelId)
                .map(row -> new VideoModelCapabilitiesView(
                        modelId,
                        range(row.getMinDurationSeconds(), row.getMaxDurationSeconds()),
                        frameRateRepository.findByModelIdOrderByFpsAsc(modelId).stream()
                                .map(VideoModelFrameRate::getFps).toList(),
                        true))
                .orElseGet(() -> new VideoModelCapabilitiesView(
                        modelId, range(fallbackMinSeconds, fallbackMaxSeconds), List.of(), false));
    }

    /** The capabilities as the assessment prompt reads them. */
    public static String describe(VideoModelCapabilitiesView capabilities) {
        List<Integer> durations = capabilities.supportedDurationsSeconds();
        String durationText = durations.isEmpty()
                ? "no supported durations are known"
                : "whole seconds from %d to %d".formatted(durations.get(0), durations.get(durations.size() - 1));
        String fpsText = capabilities.supportedFps().isEmpty()
                ? "Frame rate: not known for this model; recommend 0 for recommendedGenerationFps."
                : "Frame rate: %s fps only. The model renders at this rate and does not take a frame rate as input."
                        .formatted(capabilities.supportedFps().stream().map(String::valueOf)
                                .reduce((a, b) -> a + " or " + b).orElse(""));
        return "Model: %s\nGeneration duration: %s.\n%s".formatted(capabilities.modelId(), durationText, fpsText);
    }

    private static List<Integer> range(int min, int max) {
        return IntStream.rangeClosed(min, max).boxed().toList();
    }
}
