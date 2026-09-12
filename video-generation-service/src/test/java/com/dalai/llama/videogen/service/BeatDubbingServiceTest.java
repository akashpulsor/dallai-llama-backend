package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyDirective;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyStrategyResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BeatDubbingServiceTest {

    private final LlmGatewayClient llmGatewayClient = mock(LlmGatewayClient.class);
    private final SceneEnergyStrategyResolver sceneEnergyStrategyResolver = mock(SceneEnergyStrategyResolver.class);
    private final BeatDubbingService service = new BeatDubbingService(
            llmGatewayClient, sceneEnergyStrategyResolver,
            "elevenlabs/instant-voice-clone", "elevenlabs-tts-v1", "fal-ai/merge-audio-video");

    @Test
    void preparedCloneUsesDirectTtsWithoutCloningTheRawSampleAgain() {
        String tenantId = "tenant-1";
        UUID jobId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        DialogueBeat beat = new DialogueBeat(
                BigDecimal.ZERO, BigDecimal.ONE, "Hello", "actor-1", null,
                "prepared-eleven-voice", "elevenlabs", null, "calm", "hi-IN");
        given(sceneEnergyStrategyResolver.resolve(eq(tenantId), eq("elevenlabs-tts-v1"), eq("calm"), eq("Hello")))
                .willReturn(SceneEnergyDirective.textOnly("Hello"));
        given(llmGatewayClient.chat(eq(tenantId), anyString(), any(LlmGatewayChatRequest.class)))
                .willReturn(response("https://audio.example/dialogue.mp3"), response("https://video.example/final.mp4"));

        BeatDubbingService.DubResult result = service.dub(tenantId, jobId, projectId, List.of(beat), "https://video.example/silent.mp4");

        ArgumentCaptor<LlmGatewayChatRequest> requests = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(llmGatewayClient, times(2)).chat(eq(tenantId), anyString(), requests.capture());
        List<LlmGatewayChatRequest> calls = requests.getAllValues();
        assertThat(calls.get(0).modelId()).isEqualTo("elevenlabs-tts-v1");
        assertThat(calls.get(0).params()).containsEntry("voice_id", "prepared-eleven-voice")
                .doesNotContainKey("reference_audio_url");
        assertThat(result.finalVideoUrl()).isEqualTo("https://video.example/final.mp4");
    }

    private static LlmGatewayChatResponse response(String url) {
        return new LlmGatewayChatResponse(UUID.randomUUID(), "model", url,
                new LlmGatewayChatResponse.LlmGatewayUsage(0, 0, BigDecimal.ZERO), 1L);
    }
}