package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.entity.VideoModelFrameRate;
import com.dalai.llama.videogen.domain.entity.VideoModelGenerationCapability;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;
import com.dalai.llama.videogen.repository.VideoModelFrameRateRepository;
import com.dalai.llama.videogen.repository.VideoModelGenerationCapabilityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Only what a model can really render is ever offered -- and an unknown frame rate is never guessed. */
class VideoModelCapabilityServiceTest {

    private final VideoModelGenerationCapabilityRepository capabilities = mock(VideoModelGenerationCapabilityRepository.class);
    private final VideoModelFrameRateRepository frameRates = mock(VideoModelFrameRateRepository.class);
    private final VideoModelCapabilityService service = new VideoModelCapabilityService(capabilities, frameRates, 3, 10);

    @Test
    void aDeclaredModelOffersExactlyItsOwnDurationsAndFrameRate() {
        when(capabilities.findById("bytedance/seedance-2.0/fast")).thenReturn(Optional.of(
                VideoModelGenerationCapability.builder().modelId("bytedance/seedance-2.0/fast")
                        .minDurationSeconds(4).maxDurationSeconds(15).build()));
        when(frameRates.findByModelIdOrderByFpsAsc("bytedance/seedance-2.0/fast")).thenReturn(List.of(
                VideoModelFrameRate.builder().modelId("bytedance/seedance-2.0/fast").fps(24).build()));

        VideoModelCapabilitiesView view = service.capabilities("bytedance/seedance-2.0/fast");

        assertThat(view.declared()).isTrue();
        assertThat(view.supportedDurationsSeconds()).first().isEqualTo(4);
        assertThat(view.supportedDurationsSeconds()).last().isEqualTo(15);
        assertThat(view.supportedFps()).containsExactly(24);
        assertThat(VideoModelCapabilityService.describe(view))
                .contains("whole seconds from 4 to 15")
                .contains("24 fps only");
    }

    @Test
    void anUndeclaredModelFallsBackToTheServicesBoundsAndAdmitsItsFrameRateIsUnknown() {
        when(capabilities.findById("new/model")).thenReturn(Optional.empty());

        VideoModelCapabilitiesView view = service.capabilities("new/model");

        assertThat(view.declared()).isFalse();
        assertThat(view.supportedDurationsSeconds()).containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(view.supportedFps()).isEmpty();
        assertThat(VideoModelCapabilityService.describe(view)).contains("not known");
    }
}
