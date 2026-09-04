package com.dalai.llama.preprod.dto;

import java.util.List;

/**
 * Fat one-shot payload that gives video-generation-service everything a project prepare-scene
 * pass needs -- continuity/config/script/cast at project scope, plus each shot's
 * dialogue-beats/camera-plan/lighting-plan/images/background-music/product-reference nested.
 * Replaces ~90 sequential HTTP calls (6 project-level + 6 per-shot × N shots) with one call,
 * so a project's prepare no longer holds ~N connections open across pre-prod's Hikari pool
 * while it walks per-shot data. Assembled inside one @Transactional read (see
 * {@code PrepareBundleAssembler}), so every nested row comes from the same DB snapshot.
 *
 * <p>Fields nullable/empty when the underlying row hasn't been produced yet -- same
 * degrade-gracefully convention the per-endpoint reads already used. Video-gen's
 * ShotContextAssemblyService keeps its old per-shot code path, just consumes fields from this
 * bundle instead of firing fresh HTTP calls.
 */
public record PrepareBundleView(
        ContinuityBibleView continuityBible,
        ProjectConfigView projectConfig,
        ScriptView script,
        List<CastAssignmentView> castAssignments,
        List<CastProfileView> castProfiles,
        List<ShotBundleView> shots
) {
}
