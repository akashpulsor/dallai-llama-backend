package com.dalai.llama.videogen.dto.generationplan;

/**
 * The project's generation controls: the steps of the render path that are genuinely the creator's
 * call. {@link #DEFAULTS} is the path that always lets a shot be generated.
 *
 * <p>Length, voice and slowing are not switches. The clip is the length chosen in the video studio;
 * a shot with a line is rendered silent and gets the take the creator recorded; slowing a shorter
 * clip to the shot (always interpolated) is a step taken after generation. See ClipFinishing.
 *
 * @param preventDuplicateRenders refuse to queue a shot already rendering (it would be billed twice)
 * @param attachPreviousLastFrame start each shot from the previous shot's last frame when there is one
 */
public record GenerationControlsView(
        boolean preventDuplicateRenders,
        boolean attachPreviousLastFrame
) {
    public static final GenerationControlsView DEFAULTS = new GenerationControlsView(true, false);
}
