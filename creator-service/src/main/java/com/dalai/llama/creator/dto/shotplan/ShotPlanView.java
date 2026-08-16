package com.dalai.llama.creator.dto.shotplan;

/** Typed, read-only view combining one CreatorScriptShotPlan row's three tag blobs. */
public record ShotPlanView(
        int shotNumber,
        StoryboardTagView storyboardTag,
        LightingBuildSheetTagView lightingBuildSheetTag,
        CameraPlanSheetTagView cameraPlanSheetTag
) {
}
