package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitChoice;
import com.dalai.llama.videogen.service.generationplan.VideoModelCapabilityService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generating a shorter clip used to fail: a 1s request to a model whose minimum is 2s came back as a
 * 422, and a line longer than the clip was refused outright. The length asked for is now always one
 * the model accepts, and nothing here refuses.
 */
class RenderDurationPolicyTest {

    private static final String WAN = "alibaba/wan-3.0-prime";
    private final VideoModelCapabilityService capabilities = mock(VideoModelCapabilityService.class);
    private final RenderDurationPolicy policy = new RenderDurationPolicy(capabilities, 0.4);

    private static final GenerationControlsView FIT_OFF = GenerationControlsView.DEFAULTS;
    private static final GenerationControlsView FIT_ON = new GenerationControlsView(true, true, true, true, false, true, true);

    {
        when(capabilities.capabilities(WAN)).thenReturn(new VideoModelCapabilitiesView(
                WAN, IntStream.rangeClosed(2, 10).boxed().toList(), List.of(30), true));
    }

    private static DialogueBeat line(String start, String seconds) {
        return new DialogueBeat(new BigDecimal(start), new BigDecimal(seconds), "line", "ravi", null, "v", "p", null, null, "hi");
    }

    @Test
    void aClipShorterThanTheModelsMinimumIsAskedForAtTheMinimumNotRefused() {
        assertThat(policy.secondsToRender(WAN, 1, List.of(), DialogueFitChoice.EXTEND, FIT_OFF)).isEqualTo(2);
    }

    @Test
    void aClipLongerThanTheModelsMaximumIsCappedNotRefused() {
        assertThat(policy.secondsToRender(WAN, 14, List.of(), DialogueFitChoice.EXTEND, FIT_OFF)).isEqualTo(10);
    }

    @Test
    void withFitOffTheChosenShortLengthStandsWhateverTheLine() {
        // The creator generates 3s on purpose; post-production slows it to the planned length.
        assertThat(policy.secondsToRender(WAN, 3, List.of(line("0", "6.1")), DialogueFitChoice.EXTEND, FIT_OFF)).isEqualTo(3);
    }

    @Test
    void withFitOnTheClipGrowsToHoldItsLineAndAnImpossibleLineStillRenders() {
        assertThat(policy.secondsToRender(WAN, 4, List.of(line("0.5", "4.6")), DialogueFitChoice.EXTEND, FIT_ON)).isEqualTo(6);
        // shot-06-001's 12.9s line used to be refused; now it renders at the model's maximum.
        assertThatCode(() -> policy.secondsToRender(WAN, 5, List.of(line("0", "12.9")), DialogueFitChoice.EXTEND, FIT_ON))
                .doesNotThrowAnyException();
        assertThat(policy.secondsToRender(WAN, 5, List.of(line("0", "12.9")), DialogueFitChoice.EXTEND, FIT_ON)).isEqualTo(10);
    }

    @Test
    void keepPlannedMeansThePlannedLengthEvenWithFitOn() {
        assertThat(policy.secondsToRender(WAN, 4, List.of(line("0", "6.1")), DialogueFitChoice.KEEP_PLANNED, FIT_ON)).isEqualTo(4);
    }
}
