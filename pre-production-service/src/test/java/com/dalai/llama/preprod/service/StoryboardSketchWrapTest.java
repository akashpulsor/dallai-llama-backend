package com.dalai.llama.preprod.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link ShotImageService#wrapAsStoryboardSketch(String)} -- the standing prefix that
 * turns a plain scene description into a sketch-style image prompt. STORYBOARD contamination
 * bug (Pragya, 2026-09-22): the LLM had been writing plain photoreal-sounding sketchPrompt
 * values into shot rows, and the render-time wrap is what forces gemini-3.1-flash-lite-image
 * to return a hand-drawn panel regardless of what the DB text says.
 *
 * <p>Also guards the deliberate NOT-in-the-wrap decision: no "hero product inset" language,
 * because STORYBOARD calls attach zero product reference images and the model would invent a
 * generic product to fill the corner.
 */
class StoryboardSketchWrapTest {

    @Test
    void wrapAlwaysAsksForSketchStyle() {
        String out = ShotImageService.wrapAsStoryboardSketch(
                "Medium close-up of Anjali at her laptop, soft indoor light.");
        assertThat(out).contains("pencil-and-ink black-and-white storyboard sketch");
        assertThat(out).contains("hand-drawn look");
        assertThat(out).contains("no colour");
        assertThat(out).contains("no photorealism");
    }

    @Test
    void wrapPrependsStylingBeforeScene() {
        String scene = "Wide shot of a grandparent playing with grandchildren.";
        String out = ShotImageService.wrapAsStoryboardSketch(scene);
        int stylingAt = out.indexOf("STORYBOARD PANEL");
        int sceneAt = out.indexOf("Scene:");
        assertThat(stylingAt).isGreaterThanOrEqualTo(0);
        assertThat(sceneAt).isGreaterThan(stylingAt);
        assertThat(out).endsWith(scene);
    }

    @Test
    void wrapNeverAsksForProductInset() {
        // STORYBOARD calls attach ZERO product references (see the kind == PRODUCTION gates in
        // ShotImageService.generate). Asking Gemini for a "hero product close-up in the corner"
        // with no source image made it invent a generic product on every panel -- worse than a
        // blank corner. The wrap must not reintroduce that phrase.
        String out = ShotImageService.wrapAsStoryboardSketch("Any scene text here.");
        assertThat(out).doesNotContainIgnoringCase("hero product inset");
        assertThat(out).doesNotContainIgnoringCase("product close-up");
        assertThat(out).doesNotContainIgnoringCase("inset frame");
    }

    @Test
    void wrapNeverAsksForPhotorealismOrColor() {
        // Explicit belt-and-braces: even if the raw sketchPrompt asks for "warm indoor light"
        // or names photoreal-sounding cues, the wrapper's own instructions must veto that.
        String out = ShotImageService.wrapAsStoryboardSketch(
                "Photorealistic close-up of a person, cinematic lighting, warm tones.");
        assertThat(out).contains("Under no circumstance render this as a photo");
        assertThat(out).contains("prefer looser hand-drawn lines");
    }

    @Test
    void nullOrBlankScenePassesThroughUnchanged() {
        // A shot without a sketchPrompt is a broken row upstream; ShotImageService's caller
        // throws a badRequest if the prompt comes back null/blank, so the wrap just no-ops
        // rather than emitting a headerless orphan.
        assertThat(ShotImageService.wrapAsStoryboardSketch(null)).isNull();
        assertThat(ShotImageService.wrapAsStoryboardSketch("")).isEmpty();
        assertThat(ShotImageService.wrapAsStoryboardSketch("   ")).isEqualTo("   ");
    }
}
