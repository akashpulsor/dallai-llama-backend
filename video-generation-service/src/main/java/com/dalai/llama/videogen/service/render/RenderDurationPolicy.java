package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitChoice;
import com.dalai.llama.videogen.service.generationplan.VideoModelCapabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * How many seconds to ask the video model for. Never refuses.
 *
 * <p>The answer is always one the model accepts: a request below its minimum or above its maximum
 * is clamped, because asking anyway is a guaranteed failure (fal.ai answers a 1s Wan request with a
 * 422). Whatever length comes back, post-production conforms it to the shot's planned length.
 *
 * <p>With "fit duration to dialogue" on, the clip is also long enough to hold its measured line --
 * within the model's maximum, and unless the creator chose the planned length (KEEP_PLANNED).
 */
@Component
public class RenderDurationPolicy {

    private final VideoModelCapabilityService capabilities;
    private final double tailSeconds;

    public RenderDurationPolicy(VideoModelCapabilityService capabilities,
                                @Value("${video-gen.dialogue-fit.tail-seconds:0.4}") double tailSeconds) {
        this.capabilities = capabilities;
        this.tailSeconds = tailSeconds;
    }

    /** {@code seconds} brought inside what {@code modelId} accepts; its minimum when unset. */
    public int clamp(String modelId, Integer seconds) {
        List<Integer> supported = capabilities.capabilities(modelId).supportedDurationsSeconds();
        int min = supported.get(0);
        int max = supported.get(supported.size() - 1);
        return seconds == null ? min : Math.max(min, Math.min(max, seconds));
    }

    public int secondsToRender(String modelId, Integer requested, List<DialogueBeat> beats,
                               DialogueFitChoice choice, GenerationControlsView controls) {
        int wanted = requested == null ? 0 : requested;
        if (controls.fitDurationToDialogue() && choice != DialogueFitChoice.KEEP_PLANNED) {
            wanted = Math.max(wanted, secondsToSay(beats, tailSeconds));
        }
        return clamp(modelId, wanted == 0 ? null : wanted);
    }

    /** Where the last line ends, plus a breath, in whole seconds; 0 when nothing is spoken. */
    static int secondsToSay(List<DialogueBeat> beats, double tailSeconds) {
        double end = beats == null ? 0 : beats.stream()
                .filter(beat -> beat.effectiveSeconds() != null && beat.effectiveSeconds().signum() > 0)
                .mapToDouble(beat -> (beat.startSeconds() == null ? 0 : beat.startSeconds().doubleValue())
                        + beat.effectiveSeconds().doubleValue())
                .max().orElse(0);
        return end <= 0 ? 0 : (int) Math.ceil(BigDecimal.valueOf(end + tailSeconds).doubleValue());
    }
}
