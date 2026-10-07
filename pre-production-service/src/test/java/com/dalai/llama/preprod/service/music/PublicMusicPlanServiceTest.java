package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.GlobalMusicIdentity;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;
import com.dalai.llama.preprod.dto.music.PublicMusicPlanView;
import com.dalai.llama.preprod.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicMusicPlanServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final ProjectService projects = mock(ProjectService.class);
    private final MusicDirectorPlannerService planner = mock(MusicDirectorPlannerService.class);
    private final PublicMusicPlanService service = new PublicMusicPlanService(projects, planner);

    @BeforeEach
    void setUp() {
        when(projects.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));
    }

    @Test
    void theClientSeesTheMusicalDecisionsButNeverTheGenerationPrompt() {
        GlobalMusicIdentity identity = new GlobalMusicIdentity("Indian classical fusion", null, "warm, devotional", 72,
                "D major", null, List.of("bansuri", "tanpura"), null, null, "rising three-note phrase", null, null,
                "Hindustani", null, null, "Yaman", null, "Teentaal");
        MusicSection opening = new MusicSection(0.0, 12.0, "Dawn at the temple", "calm", 0.3, 0.1, null,
                List.of("tanpura"), null, null, null, null, null, List.of());
        when(planner.find(tenantId, projectId)).thenReturn(Optional.of(
                new MusicPlan(identity, List.of(opening), "SECRET PROMPT for the model", "resolve on the tonic", 45.0)));

        PublicMusicPlanView view = service.view("tok").orElseThrow();

        assertThat(view.identity().genre()).isEqualTo("Indian classical fusion");
        assertThat(view.identity().raga()).isEqualTo("Yaman");
        assertThat(view.identity().coreInstruments()).containsExactly("bansuri", "tanpura");
        assertThat(view.sections()).singleElement().satisfies(s -> {
            assertThat(s.storyBeat()).isEqualTo("Dawn at the temple");
            assertThat(s.endTime()).isEqualTo(12.0);
        });
        assertThat(view.totalDurationSeconds()).isEqualTo(45.0);
        assertThat(view.toString()).doesNotContain("SECRET PROMPT");
    }

    @Test
    void nothingPlannedYetIsEmptyNotAnError() {
        when(planner.find(tenantId, projectId)).thenReturn(Optional.empty());

        assertThat(service.view("tok")).isEmpty();
    }
}
