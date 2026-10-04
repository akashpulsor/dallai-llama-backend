package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;

/**
 * A "step shot": this shot's image made as the next moment of an earlier shot, by editing that
 * shot's image rather than generating from text. The people who stay keep their exact look, the
 * place and light carry over, and characters this shot does not include leave the frame -- two
 * people in the earlier shot can become one here.
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
            throw PreProductionException.badRequest("Shot " + label(source) + " has no image of this kind to step from yet");
        }
    }

    /** Folded into the image prompt as the requested change; the source image is attached first. */
    static String instruction(Shot source, String note) {
        String step = "STEP SHOT. The first attached image is shot " + label(source)
                + " of this film, the moment just before this one. Make the next frame by editing that image into the shot described above: "
                + "keep the same location, light, colour grade and camera feel, and keep every character who remains in this shot identical "
                + "in face, hair, skin tone, build and wardrobe. Characters this shot does not include leave the frame; do not add anyone the "
                + "shot does not describe. Change only the pose, action, expression and framing this shot calls for.";
        return note == null || note.isBlank() ? step : step + "\nAlso: " + note.trim();
    }

    private static String label(Shot shot) {
        return shot.getShotRef() != null && !shot.getShotRef().isBlank() ? shot.getShotRef() : String.valueOf(shot.getShotNumber());
    }
}
