package com.dalai.llama.creator.service.screenplayvideo;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;

import java.util.Map;
import java.util.UUID;

/**
 * Pairs both directions of the CreatorScriptShotPlan.storyboardTag round-trip behind one seam.
 * Before this existed, the read side (attachTo, called on every generate/regenerate) and the
 * write side (persistEditedTag, called from scene chat-edits) lived ~6,700 lines apart in
 * ScreenplayVideoService with no shared type - which is exactly how a chat-requested change
 * (wardrobe, camera, lighting, ...) could get silently reverted the next time the scene was
 * generated: the write side only patched a few fields back to the DB, the read side then
 * overwrote the in-memory scene from the (still-stale) DB row. Colocating both directions here
 * makes that bug class structurally harder to reintroduce.
 */
public interface ShotPlanTagGateway {

    /** Read side: derives scene.storyboardTag/lighting/camera/emotionalDirection/... from plan's three JSONB tags. */
    void attachTo(CreatorScriptShotPlan plan, Map<String, Object> scene);

    /** Write side: persists a chat-edited scene's full next-state storyboardTag back onto the DB shot plan. */
    void persistEditedTag(UUID scriptId, Map<String, Object> editedScene, int sceneIndex);
}
