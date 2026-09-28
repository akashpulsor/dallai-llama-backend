package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A shot can say who speaks it, overriding the cast.
 *
 * <p>The case that matters is the one the cast cannot answer at all: a shot with no character and
 * no narrator assigned used to be refused outright, and that is precisely the shot a creator picks
 * a voice for by hand. So the override is tested against a bundle with an empty cast -- if it only
 * worked once the cast already resolved, it would be useless where it is needed.
 */
class CloneVoiceShotOverrideTest {

    private final PreProductionServiceClient preprod = mock(PreProductionServiceClient.class);
    private final LlmGatewayClient gateway = mock(LlmGatewayClient.class);
    private final CloneAudioService audio = mock(CloneAudioService.class);
    private final CloneVoiceService service =
            new CloneVoiceService(preprod, gateway, audio, "clone-model", "provider", "tts-model");

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();

    /** A project whose cast can answer nothing -- no assignments, no profiles. */
    private void givenProjectWithNoCastAnd(String dubVoiceId) {
        ShotView shot = mock(ShotView.class);
        when(shot.id()).thenReturn(shotId);
        when(shot.shotRef()).thenReturn("shot-01-001");
        when(shot.voiceOver()).thenReturn("Mumbai wakes up early.");
        when(shot.primaryCharacterKey()).thenReturn(null);
        when(shot.dubVoiceId()).thenReturn(dubVoiceId);
        ShotBundleView shotBundle = new ShotBundleView(shot, List.of(), null, null, List.of(), null, null);
        when(preprod.getPrepareBundle(tenant, project)).thenReturn(Optional.of(
                new PrepareBundleView(null, null, null, List.of(), List.of(), List.of(shotBundle))));
    }

    @Test
    void theShotsOwnVoiceIsUsedEvenWhenTheCastCouldNotHaveAnsweredAtAll() {
        givenProjectWithNoCastAnd("voice-elevenlabs-rachel");
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "tts-model",
                        "data:audio/mpeg;base64,AAAA", null, 12L));
        when(audio.save(any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://media/take.mp3");

        CloneVoiceService.CloneVoiceResult result = service.cloneVoice(tenant, project, shotId, "Mumbai wakes up early.");

        assertThat(result.providerVoiceId()).isEqualTo("voice-elevenlabs-rachel");
        assertThat(result.audioUrl()).isEqualTo("https://media/take.mp3");

        // The voice actually sent to the provider, not just the one reported back.
        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), sent.capture());
        assertThat(sent.getValue().params()).containsEntry("voice_id", "voice-elevenlabs-rachel");
        assertThat(sent.getValue().modelId()).isEqualTo("tts-model");
    }

    @Test
    void blankOverrideIsNotAnOverrideAndTheCastStillDecides() {
        // Whitespace is what a cleared input posts. It must hand the shot back to the cast, which
        // here has nobody -- so the usual refusal, not a TTS call against an empty voice id.
        givenProjectWithNoCastAnd("   ");

        assertThatThrownBy(() -> service.cloneVoice(tenant, project, shotId, "Mumbai wakes up early."))
                .isInstanceOf(VideoGenException.class);
    }
}
