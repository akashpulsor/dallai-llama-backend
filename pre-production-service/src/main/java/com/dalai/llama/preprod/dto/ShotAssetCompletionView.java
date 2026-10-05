package com.dalai.llama.preprod.dto;

import java.util.UUID;

/** Per-shot "is every asset this shot needs actually generated" signal for the shots list UI (a
 * green check next to a fully-generated shot) -- distinct from {@link ShotAssetBatchJobView},
 * which only tracks aggregate progress for the batch run as a whole. {@code hasFinalFrame} marks
 * the shots whose final image exists, generated or uploaded -- the production frame for a
 * live-action shot, the motion-graphic image for a motion-graphic shot -- so the creator can see
 * at a glance which shots still need one. */
public record ShotAssetCompletionView(UUID shotId, boolean complete, boolean hasFinalFrame) {
}
