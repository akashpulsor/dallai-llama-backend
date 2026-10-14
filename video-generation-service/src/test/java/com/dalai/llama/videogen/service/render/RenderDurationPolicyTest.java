package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.service.generationplan.VideoModelCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The clip is rendered at the length the creator chose, brought inside what the model accepts -- a
 * 1s request to a model whose minimum is 2s used to come back as a 422. Nothing here lengthens a clip
 * to hold a line: that is decided before generating, by extending the shot or rephrasing the line.
 */
class RenderDurationPolicyTest {

    private static final String WAN = "alibaba/wan-3.0-prime";
    private final VideoModelCapabilityService capabilities = mock(VideoModelCapabilityService.class);
    private final RenderDurationPolicy policy = new RenderDurationPolicy(capabilities);

    {
        when(capabilities.capabilities(WAN)).thenReturn(new VideoModelCapabilitiesView(
                WAN, IntStream.rangeClosed(2, 10).boxed().toList(), List.of(30), true));
    }

    @Test
    void theChosenLengthIsRenderedAsChosen() {
        assertThat(policy.clamp(WAN, 3)).isEqualTo(3);
    }

    @Test
    void aLengthOutsideWhatTheModelAcceptsIsBroughtInsideItNotRefused() {
        assertThat(policy.clamp(WAN, 1)).isEqualTo(2);
        assertThat(policy.clamp(WAN, 14)).isEqualTo(10);
        assertThat(policy.clamp(WAN, null)).isEqualTo(2);
    }
}
