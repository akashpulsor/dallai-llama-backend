package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;

/**
 * A "step shot": this shot's image made as the next moment of an earlier shot, by editing that
 * shot's image rather than generating from text. The shot's own description decides what changes;
 * the earlier image supplies the place, the light and the people who stay.
 */
final class StepShot {

    private StepShot() {}

    /** The source has to be another shot of the same film, with an image of the kind being made. */
    static void requireUsableSource(Shot target, Shot source, boolean sourceHasImage) {
        if (source.getId().equals(target.getId())) {
            throw PreProductionException.badRequest("A shot cannot step from itself -- choose an earlier shot");
        }
        if (!source.getProjectId().equals(target.getProjectId())) {
            throw PreProductionException.badRequest("The shot to step from belongs to a different project");
        }
        if (!sourceHasImage) {
            throw PreProductionException.badRequest(capitalize(shotName(source)) + " has no image of this kind to step from yet");
        }
    }

    /** Leads the image prompt, so the model knows before reading the shot description that this is
     * an edit of the first attached image, not a new frame. Character names come from each shot's
     * primary character; null when the shot has none. The creator's own note is added separately. */
    static String instruction(Shot source, String sourceCharacter, Shot target, String targetCharacter) {
        return "STEP SHOT -- EDIT THE ATTACHED IMAGE. The first attached image is " + shotName(source)
                + " of this film, the moment just before this one. Mould that image into the shot described below: "
                + "the description decides what changes, the image supplies everything else.\n"
                + "Who is in this frame: " + whoIsInFrame(sourceCharacter, targetCharacter, target.getPeopleInFrame()) + "\n"
                + "Keep from the image: the location, the light, the colour grade, and every person who stays -- "
                + "identical in face, hair, skin tone, build and wardrobe.\n"
                + "Change to match the description: the action, poses, expressions, camera position, lens and framing. "
                + "Add no one the description does not include.\n\n"
                + "Shot to produce:";
    }

    static String whoIsInFrame(String sourceCharacter, String targetCharacter, Integer peopleInFrame) {
        if (peopleInFrame != null && peopleInFrame == 0) {
            return "no people -- remove everyone shown in the earlier image.";
        }
        String who;
        if (targetCharacter == null && sourceCharacter == null) {
            who = "neither shot has a named character; the description below says who, if anyone, is in frame.";
        } else if (targetCharacter == null) {
            who = sourceCharacter + " from the earlier image leaves the frame.";
        } else if (sourceCharacter == null) {
            who = targetCharacter + " leads this frame and is not in the earlier image.";
        } else if (targetCharacter.equalsIgnoreCase(sourceCharacter)) {
            who = targetCharacter + " stays -- the same person as in the earlier image.";
        } else {
            who = targetCharacter + " leads this frame and is not in the earlier image; " + sourceCharacter + " leaves the frame.";
        }
        return peopleInFrame == null ? who : who + " " + peopleInFrame + (peopleInFrame == 1 ? " person" : " people") + " in frame in total.";
    }

    /** "shot-01-001" stays as is; "S1-03" or a bare number reads "shot S1-03" / "shot 3". */
    static String shotName(Shot shot) {
        String ref = shot.getShotRef() != null && !shot.getShotRef().isBlank() ? shot.getShotRef().trim() : String.valueOf(shot.getShotNumber());
        return ref.toLowerCase().startsWith("shot") ? ref : "shot " + ref;
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
