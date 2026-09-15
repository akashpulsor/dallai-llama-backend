package com.dalai.llama.videogen.dto.shotcontext;

/**
 * How a MOTION_GRAPHIC shot is meant to animate, as pre-production planned it.
 *
 * <p>Present only on motion-graphic shots, and its presence is what marks one. Every other shot
 * type carries null here and takes exactly the path it always did -- this record is the whole of
 * the branch.
 *
 * <p>It exists because a motion graphic was being described to the video model with the vocabulary
 * of live action. The prompt for shot-01-007 -- an interface reveal with no camera, no cast and no
 * location -- was assembled from camera angle, lens, lighting mood and three characters' continuity
 * notes, and said nothing whatsoever about what moves. {@code animationNotes} is the one field that
 * does, and it was not being carried at all.
 *
 * @param concept        what the graphic is: "a sleek, animated reveal of the AstroNext.ai app".
 * @param onScreenText   the words that appear, verbatim and in their own script. Carried exactly as
 *                       written -- these are rendered as glyphs in the frame, so a translation or a
 *                       transliteration would put different words on screen than the film intends.
 * @param visualStyle    the look: palette, weight, era, how flat or dimensional it sits.
 * @param animationNotes how each element arrives, holds and leaves -- the fade, the wipe, the
 *                       direction text enters from, what lands first. The field the video model
 *                       actually needs and the reason this record exists.
 */
public record MotionGraphic(
        String concept,
        String onScreenText,
        String visualStyle,
        String animationNotes
) {

    /** True when there is enough here to describe a motion to the model. A plan that is all empty
     * fields is no better than no plan, and should fall back to the ordinary prompt rather than
     * asking a model to animate nothing. */
    public boolean hasPlan() {
        return hasText(concept) || hasText(onScreenText) || hasText(animationNotes);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
