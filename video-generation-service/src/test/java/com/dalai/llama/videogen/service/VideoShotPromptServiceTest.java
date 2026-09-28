package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Writing a shot's prompt instead of composing it.
 *
 * <p>The contract everything here tests is the same one: this is best-effort. Whenever it cannot
 * produce something usable it returns null, and the caller keeps the composed prompt the shot
 * would have had anyway. A shot described adequately beats one that cannot be prepared.
 */
class VideoShotPromptServiceTest {

    private final LlmGatewayClient gateway = mock(LlmGatewayClient.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    private VideoShotPromptService service(boolean enabled) {
        return new VideoShotPromptService(gateway, "gemini-2.5-flash", enabled);
    }

    private ShotContext shot() {
        ShotContext context = mock(ShotContext.class);
        when(context.shotRef()).thenReturn("shot-01-003");
        when(context.sceneType()).thenReturn("LIVE_ACTION");
        when(context.technical()).thenReturn(null);
        return context;
    }

    private void gatewayAnswers(String text) {
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "gemini-2.5-flash", text, null, 9L));
    }

    @Test
    void theComposedPlanAndTheRealBudgetAreBothSentToTheModel() {
        gatewayAnswers("A tired courier checks his phone under a sodium streetlight, 85mm, T2.8.");
        String composed = "85mm, T2.8, slow dolly in, sodium streetlight, courier checks phone";

        String written = service(true).writePrompt(tenant, project, shot(), composed, 20000);

        assertThat(written).startsWith("A tired courier");

        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), sent.capture());
        assertThat(sent.getValue().taskKey()).isEqualTo("VIDEO_SHOT_PROMPT");
        // The budget has to travel with the request: writing TO the limit is what removes the
        // need for the lossy compression pass afterwards.
        assertThat(sent.getValue().templateVariables())
                .containsEntry("maxChars", "20000")
                .containsEntry("composedPrompt", composed)
                .containsEntry("shotType", "LIVE_ACTION");
    }

    @Test
    void aPromptOverTheModelsBudgetIsRefusedRatherThanPassedOn() {
        // Handing an over-budget prompt on would send it straight to compression, which is the
        // lossy rewrite this call exists to avoid. The composed prompt is the honest fallback.
        gatewayAnswers("x".repeat(1201));

        assertThat(service(true).writePrompt(tenant, project, shot(), "the plan", 1200)).isNull();
    }

    @Test
    void aGatewayFailureLeavesTheShotOnItsComposedPrompt() {
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenThrow(new RuntimeException("gateway down"));

        assertThat(service(true).writePrompt(tenant, project, shot(), "the plan", 20000)).isNull();
    }

    @Test
    void anEmptyAnswerLeavesTheShotOnItsComposedPrompt() {
        gatewayAnswers("   ");

        assertThat(service(true).writePrompt(tenant, project, shot(), "the plan", 20000)).isNull();
    }

    @Test
    void aFencedAnswerIsUnwrapped() {
        gatewayAnswers("```\nA courier under a streetlight.\n```");

        assertThat(service(true).writePrompt(tenant, project, shot(), "the plan", 20000))
                .isEqualTo("A courier under a streetlight.");
    }

    @Test
    void disabledOrWithNothingComposedItNeverCallsTheGateway() {
        assertThat(service(false).writePrompt(tenant, project, shot(), "the plan", 20000)).isNull();
        assertThat(service(true).writePrompt(tenant, project, shot(), "  ", 20000)).isNull();
        verifyNoInteractions(gateway);
    }
}
