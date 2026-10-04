package com.dalai.llama.videogen.dto.generationplan;

/**
 * The project's generation controls: every step of the render path that can stop or reshape a shot,
 * as a switch the creator owns. {@link #DEFAULTS} is the path that always lets a shot be generated.
 *
 * @param fitDurationToDialogue   lengthen the clip to hold its measured dialogue, up to the model's maximum;
 *                                never refuses. Off generates at exactly the chosen duration
 * @param autoDubDialogue         generate silent and lay the cloned voice on afterwards; off lets the
 *                                model perform the line itself
 * @param mixBackgroundMusic      lay the shot's music bed under the finished clip
 * @param preventDuplicateRenders refuse to queue a shot already rendering (it would be billed twice)
 * @param attachPreviousLastFrame start each shot from the previous shot's last frame when there is one
 * @param conformToPlannedDuration bring a clip generated at another length to the planned length in
 *                                 post-production: slowed down when shorter, trimmed when longer
 * @param interpolateWhenSlowing   synthesise the in-between frames when slowing down, so motion stays smooth
 */
public record GenerationControlsView(
        boolean fitDurationToDialogue,
        boolean autoDubDialogue,
        boolean mixBackgroundMusic,
        boolean preventDuplicateRenders,
        boolean attachPreviousLastFrame,
        boolean conformToPlannedDuration,
        boolean interpolateWhenSlowing
) {
    public static final GenerationControlsView DEFAULTS =
            new GenerationControlsView(false, true, true, true, false, true, true);
}
