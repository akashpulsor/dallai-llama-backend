package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView;
import com.dalai.llama.creativeplanning.dto.ProjectCreativeContextView.ReferenceMediaType;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceImageView;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceVideoView;
import com.dalai.llama.creativeplanning.dto.ReferenceImageAnalysisView;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectCreativeContextServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();
    private final LockedIdeaRepository ideas = mock(LockedIdeaRepository.class);
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final ProjectReferenceImageService images = mock(ProjectReferenceImageService.class);
    private final ProjectReferenceVideoService videos = mock(ProjectReferenceVideoService.class);
    private final ProjectCreativeContextService service = new ProjectCreativeContextService(ideas, requirements, images, videos);

    @Test
    void resolvesTheIdeaBriefAndRealReferenceAssetsByTheirOriginalIds() {
        UUID imageId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        when(ideas.findTopByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(Optional.of(LockedIdea.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).projectRequirementId(requirementId)
                .title("The Verified Difference").concept("Trust in home services").build()));
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId).briefText("City Professionals launch film").durationSeconds(30)
                .includeVideoShots(true).videoShotsIntent("show the real technician").build()));
        when(images.list(requirementId)).thenReturn(List.of(new ProjectReferenceImageView(imageId, requirementId, "creator-assets",
                "refs/a.jpg", "https://signed/a", new ReferenceImageAnalysisView("A kitchen", "teal", "documentary", null, null))));
        when(videos.list(requirementId)).thenReturn(List.of(new ProjectReferenceVideoView(videoId, requirementId, "creator-assets",
                "refs/b.mp4", "https://signed/b", "b.mp4", "video/mp4", 1024L)));

        ProjectCreativeContextView context = service.forProject(tenantId, projectId);

        assertThat(context.idea().title()).isEqualTo("The Verified Difference");
        assertThat(context.brief().durationSeconds()).isEqualTo(30);
        assertThat(context.references()).extracting(ProjectCreativeContextView.ReferenceAsset::assetId).containsExactly(imageId, videoId);
        assertThat(context.references().get(0).mediaType()).isEqualTo(ReferenceMediaType.IMAGE);
        assertThat(context.references().get(0).analysis()).contains("Description: A kitchen", "Dominant colours: teal");
        assertThat(context.references().get(1).clientInstruction()).isEqualTo("show the real technician");
        assertThat(context.references().get(1).signedUrl()).isEqualTo("https://signed/b");
    }

    @Test
    void aProjectFromAnotherTenantHasNoContext() {
        when(ideas.findTopByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(Optional.of(LockedIdea.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID()).projectId(projectId).build()));

        assertThatThrownBy(() -> service.forProject(tenantId, projectId)).isInstanceOf(CreativePlanningException.class);
    }
}
