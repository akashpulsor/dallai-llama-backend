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
        ShotProductReferenceView productReference
) {
}
