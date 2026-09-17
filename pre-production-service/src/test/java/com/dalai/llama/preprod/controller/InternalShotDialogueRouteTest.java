package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotDialogueView;
import com.dalai.llama.preprod.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other half of the contract post-production's {@code HttpPreProductionClient} asserts.
 *
 * <p>That client pins the URL it sends. This pins that the URL is actually served, and by real
 * Spring request mapping rather than a string comparison -- the two tests together are what make a
 * rename on one side fail loudly instead of becoming a 404 nobody sees until the box.
 *
 * <p>The creator-facing twin of this route stays exactly as it is. It simply has no browser caller:
 * {@code DialogueController}'s own javadoc names this pipeline as who it was built for, and being on
 * {@code /v1/**} it demanded a JWT that a background thread has no way to produce.
 */
class InternalShotDialogueRouteTest {

    private final DialogueDetailsService dialogueDetailsService = mock(DialogueDetailsService.class);

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new InternalShotAssemblyController(
                mock(ContinuityBibleService.class),
                mock(ProjectConfigService.class),
                mock(CastAssignmentService.class),
                mock(CastProfileService.class),
                mock(ScriptGenerationService.class),
                mock(ShotListGenerationService.class),
                mock(ShotDialogueBeatService.class),
                mock(CameraPlanService.class),
                mock(LightingPlanService.class),
                mock(ShotImageService.class),
                mock(ShotBackgroundMusicService.class),
                mock(ShotProductReferenceService.class),
                mock(PrepareBundleAssembler.class),
                dialogueDetailsService)).build();
    }

    @Test
    void servesAShotsDialogueOnTheInternalPath() throws Exception {
        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(dialogueDetailsService.getShotDialogue(eq(tenantId), eq(projectId), eq("S1")))
                .thenReturn(new ShotDialogueView(projectId, null, "S1", 3, "NARRATOR",
                        "Say the thing.", "en", "en-IN", "https://example/audio.wav"));

        mockMvc().perform(get("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotRef}/dialogue",
                        tenantId, projectId, "S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shotRef").value("S1"))
                .andExpect(jsonPath("$.dialogueScript").value("Say the thing."))
                .andExpect(jsonPath("$.referenceAudioUrl").value("https://example/audio.wav"));

        // Tenant is read from the path. There is no JWT on this chain to take it from, which is the
        // entire reason a background thread can call it at all.
        verify(dialogueDetailsService).getShotDialogue(tenantId, projectId, "S1");
    }
}
