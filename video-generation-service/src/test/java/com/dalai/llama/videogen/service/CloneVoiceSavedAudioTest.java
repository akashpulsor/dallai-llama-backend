package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloneVoiceSavedAudioTest {
    private final PreProductionServiceClient preprod = mock(PreProductionServiceClient.class);
    private final LlmGatewayClient gateway = mock(LlmGatewayClient.class);
    private final CloneAudioService audio = mock(CloneAudioService.class);
    private final CloneVoiceService service = new CloneVoiceService(preprod, gateway, audio, "clone", "provider", "tts");

    @Test
    void pageLoadReturnsSavedAudioWithoutSynthesisAndOmitsDeletedOrChangedBeats() {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), shotId = UUID.randomUUID(), beatId = UUID.randomUUID();
        ShotView shot = mock(ShotView.class);
        when(shot.id()).thenReturn(shotId);
        ShotDialogueBeatView beat = new ShotDialogueBeatView(beatId, 0, null, null, "Hi", "actor", "voice");
        ShotBundleView shotBundle = new ShotBundleView(shot, List.of(beat), null, null, List.of(), null, null);
        when(preprod.getPrepareBundle(tenant, project)).thenReturn(Optional.of(
                new PrepareBundleView(null, null, null, List.of(), List.of(), List.of(shotBundle))));
        var current = new CloneAudioService.CloneAudioView(shotId, beatId, "Hi", "cloned", "voice", "https://media/new", 1200, java.time.OffsetDateTime.now(), false);
        var changed = new CloneAudioService.CloneAudioView(shotId, beatId, "Old text", "cloned", "voice", "https://media/old", 1200, java.time.OffsetDateTime.now(), false);
        var deleted = new CloneAudioService.CloneAudioView(shotId, UUID.randomUUID(), "Hi", "cloned", "voice", "https://media/deleted", 1200, java.time.OffsetDateTime.now(), false);
        when(audio.list(tenant, project)).thenReturn(List.of(current, changed, deleted));
        assertThat(service.listSavedAudio(tenant, project)).containsExactly(current);
        verifyNoInteractions(gateway);
    }

    @Test
    void missingProjectDoesNotReadAudio() {
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID();
        when(preprod.getPrepareBundle(tenant, project)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listSavedAudio(tenant, project)).isInstanceOf(VideoGenException.class);
        verifyNoInteractions(audio, gateway);
    }
}
