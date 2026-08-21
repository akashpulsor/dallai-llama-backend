package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.dto.shotcontext.ShotContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Level 0 of the critic hierarchy: deterministic, no LLM call. Things that are simply wrong,
 * not matters of judgment -- missing aspect ratio, zero/negative duration, a PRODUCT_HERO shot
 * with no product reference, no camera framing at all. Run first, before any role critic, because
 * there's no point spending three LLM calls critiquing a plan that's incomplete on its face.
 * Findings from here are always P1 (blocking) by construction.
 */
final class HardConstraintCheck {

    private HardConstraintCheck() {
    }

    static List<CriticFindingItem> run(ShotContext plan) {
        List<CriticFindingItem> findings = new ArrayList<>();

        if (plan.technical() == null || plan.technical().aspectRatio() == null) {
            findings.add(finding("No aspect ratio specified.", "Cannot dispatch to video-generation-service without a target aspect ratio.",
                    "Technical.aspectRatio is missing from the assembled shot plan.", "Set an explicit aspect ratio before dispatch."));
        }
        if (plan.technical() == null || plan.technical().durationSeconds() == null || plan.technical().durationSeconds() <= 0) {
            findings.add(finding("Shot duration is missing or non-positive.", "Generation will fail or produce an unusable clip.",
                    "Technical.durationSeconds is null or <= 0.", "Set a positive duration in seconds."));
        }
        if (plan.camera() == null || (plan.camera().shotSize() == null && (plan.camera().cameraNote() == null || plan.camera().cameraNote().isBlank()))) {
            findings.add(finding("No camera framing specified.", "The model has no cinematographic instruction at all for this shot.",
                    "Camera.shotSize and Camera.cameraNote are both empty.", "Specify at least a shot size or a camera note."));
        }
        if (plan.productBrand() != null && Boolean.TRUE.equals(plan.productBrand().isProductHeroShot())
                && isBlank(plan.productBrand().productRefBucket()) && isBlank(plan.productBrand().productRefObjectKey())) {
            findings.add(finding("Shot is flagged as a product hero shot but has no product reference image.",
                    "The generated video will not actually show the product it's supposed to showcase.",
                    "ProductBrand.isProductHeroShot=true but productRefBucket/productRefObjectKey are both empty.",
                    "Assign a product reference image before dispatch, or unset isProductHeroShot."));
        }

        return findings;
    }

    private static CriticFindingItem finding(String observation, String risk, String cause, String correction) {
        return new CriticFindingItem(observation, risk, cause, correction, "P1");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
