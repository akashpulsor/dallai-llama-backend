package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.dto.music.PublicMusicPlanView;
import com.dalai.llama.preprod.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** The project's score plan for the client review page, reached by the review token. */
@Service
@RequiredArgsConstructor
public class PublicMusicPlanService {

    private final ProjectService projectService;
    private final MusicDirectorPlannerService plannerService;

    /** Empty when nothing has been planned yet -- the page simply shows no music section. */
    public Optional<PublicMusicPlanView> view(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return plannerService.find(identity.tenantId(), identity.projectId()).map(PublicMusicPlanView::from);
    }
}
