package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.ReferenceKind;

/**
 * An already-rendered image belonging to the shot itself -- its storyboard frame, its approved
 * production still, its motion-graphic preview -- carried on the {@link ShotContext} so
 * {@code ShotGenerationOrchestrator.saveReferences} can persist it as a
 * {@code shot_prompt_reference} row.
 *
 * <p>Before this existed the assembler only picked up the LIGHTING and CAMERA_PLAN diagrams, so
 * the one image that matters most to an image-to-video model -- the actual frame the shot is
 * supposed to look like -- never reached the prompt. The prompt described the shot in words while
 * the picture of it sat unused in pre-production.
 */
public record ReferenceFrame(
        ReferenceKind kind,
        String bucket,
        String objectKey
) {
}
