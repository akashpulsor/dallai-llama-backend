package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardImageGenerationServiceTest {

    @Test
    void leavesShotSizedPromptUnchanged() {
        String prompt = "shot 2: preserve the exact pack, use the reference as inspiration for lighting";

        assertThat(StoryboardImageGenerationService.completeProviderPrompt(prompt)).isEqualTo(prompt);
    }

    @Test
    void preservesLargeButValidShotPacketWithoutTrimmingAnyDirectorDetail() {
        String prompt = "COMPLETE CURRENT-SHOT DIRECTOR PACKET\n" + "per-second camera lighting focus detail ".repeat(2_000)
                + "\nCLIENT-CONFIRMED FRAME REVISION: keep shot 2 continuous with shot 1 and shot 3";

        String complete = StoryboardImageGenerationService.completeProviderPrompt(prompt);

        assertThat(complete).isEqualTo(prompt);
    }

    @Test
    void rejectsPathologicalPacketInsteadOfSilentlyRemovingDetails() {
        String prompt = "x".repeat(1_500_001);

        assertThatThrownBy(() -> StoryboardImageGenerationService.completeProviderPrompt(prompt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No prompt content was removed");
    }
}
