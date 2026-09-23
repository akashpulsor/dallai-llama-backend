package com.dalai.llama.preprod.service.generation;

import com.dalai.llama.preprod.domain.AspectRatio;
import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ProductReferenceClassification;
import com.dalai.llama.preprod.domain.ShotSize;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the PRODUCTION-still prompt assembly in {@link ShotImagePromptBuilder}. The rewrite
 * (2026-09-23) addressed two confirmed regressions:
 *   1. The rich {@code cine*} taxonomy the shot-list generator fills was being dropped -- every
 *      production still ran on only ~5 flat fields (shotType/cameraShotSize/cameraAngle/
 *      lensSuggestion/composition), so distinct shots produced near-identical anchor frames.
 *   2. {@code cameraMovement} ("slow push-in") was being emitted into a still-image prompt where
 *      a temporal verb has no meaning -- confirmed to occasionally warp perspective in the frame.
 *
 * <p>The rewrite also swapped the reference precedence: previously a shot with BOTH a cast
 * profile and a product reference silently dropped the cast face (product-wins ternary). It now
 * emits both identity blocks; {@link com.dalai.llama.preprod.service.ShotImageService} attaches
 * both images to the Gemini call in the same order.
 */
class ShotImagePromptBuilderProductionTest {

    @Test
    void includesRequiredFieldsAndCoreCamera() {
        Shot shot = minimalShot();
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).contains("Shot type: DIALOGUE");
        assertThat(out).contains("What happens: Anjali smiles at her laptop");
        assertThat(out).contains("Camera: MCU shot, EYE_LEVEL angle, 50mm lens.");
        assertThat(out).contains("Composition: rule-of-thirds, subject slightly left");
        assertThat(out).contains("Location: home office, GOLDEN_HOUR");
        assertThat(out).contains("Lighting mood: SOFT");
        assertThat(out).contains("Output: RATIO_9_16 composition");
    }

    @Test
    void omitsCameraMovementFromTheStillPrompt() {
        // Regression guard: cameraMovement is a temporal verb ("slow push-in") that made Gemini
        // warp perspective in a still. See ShotImagePromptBuilder class doc for the reasoning.
        Shot shot = minimalShot();
        shot.setCameraMovement("slow push-in from left");
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).doesNotContain("slow push-in");
        assertThat(out).doesNotContain("push-in");
        assertThat(out).doesNotContainIgnoringCase("Camera movement");
    }

    @Test
    void omitsScriptLineFromTheStillPrompt() {
        // Script lines (quoted spoken narration) reproducibly triggered identity-conditioned
        // generation failures against Gemini -- 5/5 fails with the line, 3/3 success without.
        // A still has no audio; a quoted spoken line next to "no on-image text or labels"
        // contradicts itself and the model has to resolve it somehow.
        Shot shot = minimalShot();
        shot.setScriptLine("She says: I finally sleep through the night.");
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).doesNotContain("finally sleep through the night");
        assertThat(out).doesNotContain("She says:");
    }

    @Test
    void includesCineTaxonomyWhenPopulated() {
        // Confirms the rich taxonomy actually reaches the prompt -- this is what the bug was.
        Shot shot = minimalShot();
        shot.setCinePositionHeight("eye-level");
        shot.setCinePositionDistance("intimate, 3 feet");
        shot.setCineLensFocalLength("50mm");
        shot.setCineLensCharacter("clinical, low distortion");
        shot.setCineFraming("centered, symmetrical");
        shot.setCineFocusTarget("subject's eyes");
        shot.setCineDepthOfField("shallow, f/2.8");
        shot.setCineContrast("high, moody");
        shot.setCineGrain("fine 35mm digital");

        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).contains("Camera geometry: height eye-level, distance intimate, 3 feet");
        assertThat(out).contains("Lens: focal length 50mm, character clinical, low distortion");
        assertThat(out).contains("Framing: framing centered, symmetrical");
        assertThat(out).contains("Focus: target subject's eyes, depth of field shallow, f/2.8");
        assertThat(out).contains("Image character: contrast high, moody, grain fine 35mm digital");
    }

    @Test
    void omitsSectionsWhenAllFieldsAreNull() {
        // Regression guard against the old "not specified, not specified, not specified" bulk.
        // A shot without any lens data should not emit a "Lens:" line at all.
        Shot shot = minimalShot();
        // cine* fields left null on purpose
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).doesNotContain("Camera geometry:");
        assertThat(out).doesNotContain("Lens:");
        assertThat(out).doesNotContain("Framing:");
        assertThat(out).doesNotContain("Focus:");
        assertThat(out).doesNotContain("Image character:");
        assertThat(out).doesNotContain("Performance:");
        assertThat(out).doesNotContain("Lighting plan:");
    }

    @Test
    void omitsIndividualNullFieldsWithinAPopulatedSection() {
        // Half-populated section: emit only the non-null entries, never "height not specified".
        Shot shot = minimalShot();
        shot.setCinePositionHeight("eye-level");
        shot.setCinePositionDistance(null);
        shot.setCinePositionLateral(null);
        shot.setCinePositionElevation("neutral");
        shot.setCinePositionOrientation(null);
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).contains("Camera geometry: height eye-level, elevation neutral");
        assertThat(out).doesNotContain("distance not specified");
        assertThat(out).doesNotContain("lateral not specified");
    }

    @Test
    void includesPerformanceEvenWithoutCastProfile() {
        // Previously expression/emotion/bodyLanguage lived inside the cast-identity branch, so a
        // product-only (or reference-less) PRODUCTION shot lost its performance direction.
        Shot shot = minimalShot();
        shot.setExpression("gentle, half-smile");
        shot.setEmotion("relieved, unguarded");
        shot.setBodyLanguage("leaning forward, hands relaxed");
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).contains("Performance: expression gentle, half-smile, "
                + "emotion relieved, unguarded, body language leaning forward, hands relaxed");
    }

    @Test
    void includesLightingPlanSliceWhenProvided() {
        // Direction/quality slots reach the still; setup steps and gear part numbers do NOT
        // (those belong on the lighting-sheet image, not the finished frame).
        Shot shot = minimalShot();
        LightingPlan plan = new LightingPlan();
        plan.setCinematicIntent("warm intimate cocoon");
        plan.setKeyLightGear("softbox 24in, camera-left, 45 degrees");
        plan.setFillLightGear("bounce card, camera-right");
        plan.setBuildSteps("1. Place softbox\n2. Angle card\n3. Meter for face");

        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null, plan);
        assertThat(out).contains("Lighting plan: cinematic intent warm intimate cocoon");
        assertThat(out).contains("key light softbox 24in, camera-left, 45 degrees");
        assertThat(out).contains("fill bounce card, camera-right");
        // Setup steps must never land in the still prompt.
        assertThat(out).doesNotContain("Place softbox");
        assertThat(out).doesNotContain("Meter for face");
    }

    @Test
    void twoArgOverloadDelegatesWithNullLightingPlan() {
        // The convenience overload exists for tests and for shots without a plan yet -- it must
        // not synthesize a "Lighting plan:" section from nothing.
        Shot shot = minimalShot();
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null);
        assertThat(out).doesNotContain("Lighting plan:");
    }

    @Test
    void combinedCastAndProductEmitsBothIdentityBlocksCastFirst() {
        // The critical fix: previously the product-wins ternary silently dropped the cast face
        // for combined shots. Both blocks must appear, cast first (matches the image-attachment
        // order in ShotImageService).
        Shot shot = minimalShot();
        CastProfile cast = new CastProfile();
        cast.setDisplayName("Anjali");
        cast.setDescription("early-30s working mother");
        cast.setGender("female");

        ShotProductReference product = new ShotProductReference();
        product.setClassification(ProductReferenceClassification.CAST);

        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, cast, product);
        int castAt = out.indexOf("Primary subject: Anjali");
        int productAt = out.indexOf("PRIMARY IDENTITY REFERENCE", castAt + 1);
        assertThat(castAt).as("cast identity block present").isGreaterThanOrEqualTo(0);
        assertThat(productAt).as("product identity block present after cast").isGreaterThan(castAt);
        // Gendered pronoun should propagate through (empirical Gemini finding -- see class doc
        // on personIdentityLockInstruction).
        assertThat(out).contains("keep her face");
    }

    @Test
    void productOnlyEmitsProductBlockAndNoCastBlock() {
        Shot shot = minimalShot();
        ShotProductReference product = new ShotProductReference();
        product.setClassification(ProductReferenceClassification.CAST);
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, null, product);
        assertThat(out).contains("PRIMARY IDENTITY REFERENCE");
        assertThat(out).doesNotContain("Primary subject:");
    }

    @Test
    void castOnlyEmitsCastBlockAndNoProductBlock() {
        Shot shot = minimalShot();
        CastProfile cast = new CastProfile();
        cast.setDisplayName("Anjali");
        cast.setGender("female");
        String out = ShotImagePromptBuilder.buildProductionPrompt(shot, cast, null);
        assertThat(out).contains("Primary subject: Anjali");
        assertThat(out).contains("PRIMARY IDENTITY REFERENCE for this subject");
        // Should not double-print an unrelated product reference block.
        assertThat(out).doesNotContain("A style reference photo is attached");
    }

    private static Shot minimalShot() {
        Shot s = new Shot();
        s.setShotType(ShotType.DIALOGUE);
        s.setAction("Anjali smiles at her laptop");
        s.setCameraShotSize(ShotSize.MCU);
        s.setCameraAngle("EYE_LEVEL");
        s.setLensSuggestion("50mm");
        s.setComposition("rule-of-thirds, subject slightly left");
        s.setLocation("home office");
        s.setTimeOfDay(TimeOfDay.GOLDEN_HOUR);
        s.setLightingMood(MoodProfile.SOFT);
        s.setAspectRatio(AspectRatio.RATIO_9_16);
        return s;
    }
}
