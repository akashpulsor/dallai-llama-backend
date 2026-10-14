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
import static org.mockito.ArgumentMatchers.eq;
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
    void aCharacterChosenToReadTheShotIsHeardInTheirOwnClonedVoice() {
        // Neha is in the picture; the creator chose Ravi to read the line over it.
        UUID ravi = UUID.randomUUID();
        CastProfileView raviProfile = mock(CastProfileView.class);
        when(raviProfile.id()).thenReturn(ravi);
        when(raviProfile.clonedVoiceId()).thenReturn("voice-ravi-clone");
        when(raviProfile.clonedVoiceProviderId()).thenReturn("elevenlabs");
        when(raviProfile.voiceIdentityType()).thenReturn("HUMAN");
        ShotView shot = mock(ShotView.class);
        when(shot.id()).thenReturn(shotId);
        when(shot.shotRef()).thenReturn("shot-02-003");
        when(shot.voiceOver()).thenReturn("Sab theek ho gaya.");
        when(shot.primaryCharacterKey()).thenReturn("neha");
        when(shot.dubCastProfileId()).thenReturn(ravi);
        ShotBundleView shotBundle = new ShotBundleView(shot, List.of(), null, null, List.of(), null, null);
        when(preprod.getPrepareBundle(tenant, project)).thenReturn(Optional.of(
                new PrepareBundleView(null, null, null, List.of(), List.of(raviProfile), List.of(shotBundle))));
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "tts-model", "data:audio/mpeg;base64,AAAA", null, 12L));
        when(audio.save(any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://media/take.mp3");

        CloneVoiceService.CloneVoiceResult result = service.cloneVoice(tenant, project, shotId, "Sab theek ho gaya.");

        assertThat(result.providerVoiceId()).isEqualTo("voice-ravi-clone");
        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), sent.capture());
        assertThat(sent.getValue().params()).containsEntry("voice_id", "voice-ravi-clone");
    }

    @Test
    void blankOverrideIsNotAnOverrideAndTheCastStillDecides() {
        // Whitespace is what a cleared input posts. It must hand the shot back to the cast, which
        // here has nobody -- so the usual refusal, not a TTS call against an empty voice id.
        givenProjectWithNoCastAnd("   ");

        assertThatThrownBy(() -> service.cloneVoice(tenant, project, shotId, "Mumbai wakes up early."))
                .isInstanceOf(VideoGenException.class);
    }

    @Test
    void inATwoPersonShotEachLineIsRecordedInItsOwnSpeakersVoice() {
        // Meera is the shot's main character; Ravi answers her. His line must be his voice.
        UUID meeraProfileId = UUID.randomUUID();
        UUID raviProfileId = UUID.randomUUID();
        CastProfileView meeraProfile = mock(CastProfileView.class);
        when(meeraProfile.id()).thenReturn(meeraProfileId);
        when(meeraProfile.clonedVoiceId()).thenReturn("voice-meera");
        when(meeraProfile.clonedVoiceProviderId()).thenReturn("elevenlabs");
        when(meeraProfile.voiceIdentityType()).thenReturn("HUMAN");
        CastProfileView raviProfile = mock(CastProfileView.class);
        when(raviProfile.id()).thenReturn(raviProfileId);
        when(raviProfile.clonedVoiceId()).thenReturn("voice-ravi");
        when(raviProfile.clonedVoiceProviderId()).thenReturn("elevenlabs");
        when(raviProfile.voiceIdentityType()).thenReturn("HUMAN");

        UUID meeraCharacter = UUID.randomUUID();
        UUID raviCharacter = UUID.randomUUID();
        com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptCharacterView meeraChar =
                mock(com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptCharacterView.class);
        when(meeraChar.id()).thenReturn(meeraCharacter);
        when(meeraChar.characterKey()).thenReturn("meera");
        com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptCharacterView raviChar =
                mock(com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptCharacterView.class);
        when(raviChar.id()).thenReturn(raviCharacter);
        when(raviChar.characterKey()).thenReturn("ravi");
        com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptView script =
                mock(com.dalai.llama.videogen.service.preproduction.PreProductionViews.ScriptView.class);
        when(script.characters()).thenReturn(List.of(meeraChar, raviChar));
        com.dalai.llama.videogen.service.preproduction.PreProductionViews.CastAssignmentView meeraCast =
                mock(com.dalai.llama.videogen.service.preproduction.PreProductionViews.CastAssignmentView.class);
        when(meeraCast.scriptCharacterId()).thenReturn(meeraCharacter);
        when(meeraCast.castProfileId()).thenReturn(meeraProfileId);
        com.dalai.llama.videogen.service.preproduction.PreProductionViews.CastAssignmentView raviCast =
                mock(com.dalai.llama.videogen.service.preproduction.PreProductionViews.CastAssignmentView.class);
        when(raviCast.scriptCharacterId()).thenReturn(raviCharacter);
        when(raviCast.castProfileId()).thenReturn(raviProfileId);

        ShotView shot = mock(ShotView.class);
        when(shot.id()).thenReturn(shotId);
        when(shot.shotRef()).thenReturn("shot-03-002");
        when(shot.primaryCharacterKey()).thenReturn("meera");
        UUID askBeat = UUID.randomUUID();
        UUID answerBeat = UUID.randomUUID();
        var beats = List.of(
                new com.dalai.llama.videogen.service.preproduction.PreProductionViews.ShotDialogueBeatView(
                        askBeat, 0, new java.math.BigDecimal("1.2"), java.math.BigDecimal.ONE, "Ho gaya?", "meera", null),
                new com.dalai.llama.videogen.service.preproduction.PreProductionViews.ShotDialogueBeatView(
                        answerBeat, 1, new java.math.BigDecimal("3.5"), java.math.BigDecimal.ONE, "Theek hai.", "ravi", null));
        ShotBundleView shotBundle = new ShotBundleView(shot, beats, null, null, List.of(), null, null);
        when(preprod.getPrepareBundle(tenant, project)).thenReturn(Optional.of(
                new PrepareBundleView(null, null, script, List.of(meeraCast, raviCast), List.of(meeraProfile, raviProfile), List.of(shotBundle))));
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "tts-model", "data:audio/mpeg;base64,AAAA", null, 12L));
        when(audio.save(any(), any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://media/take.mp3");

        assertThat(service.cloneVoice(tenant, project, shotId, "Theek hai.").providerVoiceId()).isEqualTo("voice-ravi");
        assertThat(service.cloneVoice(tenant, project, shotId, "Ho gaya?").providerVoiceId()).isEqualTo("voice-meera");
        // Recording the shot's whole line records each line on its own, in its own speaker's voice.
        when(shot.voiceOver()).thenReturn("Ho gaya? Theek hai.");
        org.mockito.Mockito.clearInvocations(gateway, audio);
        service.cloneVoice(tenant, project, shotId, "Ho gaya? Theek hai.");
        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway, org.mockito.Mockito.times(2)).chat(anyString(), anyString(), sent.capture());
        assertThat(sent.getAllValues()).extracting(r -> r.params().get("voice_id")).containsExactly("voice-meera", "voice-ravi");
        verify(audio).save(any(), any(), any(), eq(askBeat), eq("Ho gaya?"), anyString(), eq("voice-meera"), anyString());
        verify(audio).save(any(), any(), any(), eq(answerBeat), eq("Theek hai."), anyString(), eq("voice-ravi"), anyString());
    }
}
