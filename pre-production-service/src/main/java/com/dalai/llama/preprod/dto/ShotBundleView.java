package com.dalai.llama.preprod.dto;

import java.util.List;

/** One shot plus every downstream row video-gen assembly reads for it. Nested inside
 * {@link PrepareBundleView#shots()}. Optional-shaped fields (cameraPlan, lightingPlan,
 * backgroundMusic, productReference) come back null when the corresponding row doesn't exist
 * yet -- same graceful-degrade convention as pre-prod's per-endpoint reads. */
public record ShotBundleView(
        ShotView shot,
        List<ShotDialogueBeatView> dialogueBeats,
        CameraPlanView cameraPlan,
        LightingPlanView lightingPlan,
        List<ShotImageView> shotImages,
        ShotBackgroundMusicView backgroundMusic,
        ShotProductReferenceView productReference,
        /** Derived once when the shot is planned, carried here so video-generation-service
         * reads the cue sheet instead of paying to re-derive it on every prepare. */
        List<ShotFoleyCueView> foleyCues,
        /** How a MOTION_GRAPHIC shot is meant to animate -- its concept, the text on screen, the
         * visual style and, crucially, the animation notes saying how each element arrives.
         *
         * <p>Carried because without it video-generation-service composed a motion graphic's prompt
         * from the ordinary shot columns: camera angle, lighting mood, character continuity. A
         * motion graphic has no camera and no characters, so the prompt said nothing about the one
         * thing that matters -- what moves, and how -- and the model was left to invent it. Null for
         * every other shot type. */
        MotionGraphicPlanView motionGraphicPlan
) {
}
