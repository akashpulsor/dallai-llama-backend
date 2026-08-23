package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ProjectStatus;

import java.util.List;
import java.util.UUID;

/** What the client's unauthenticated review page reads -- deliberately excludes tenant id,
 * internal cross-references, and anything not meant for an external viewer, same convention as
 * creative-planning-service's PublicProjectRequirementView. */
public record PublicProjectPackageView(
        UUID projectId,
        String name,
        ProjectStatus status,
        ScriptView script,
        ScreenplayView screenplay,
        List<PublicCastMemberView> cast,
        List<PublicShotView> shots
) {
    public record PublicCastMemberView(
            String characterName,
            String characterType,
            String profileDisplayName,
            String profileImageUrl
    ) {
    }

    public record PublicShotView(
            UUID id,
            String shotRef,
            Integer shotNumber,
            String shotType,
            String action,
            List<ShotImageView> images
    ) {
    }
}
