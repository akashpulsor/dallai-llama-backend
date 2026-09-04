package com.dalai.llama.preprod.service;

import java.util.UUID;

/** One unit of work in a "Generate all shot assets" batch (see {@link ShotAssetBatchExecutor}) --
 * {@code shotNumber}/{@code totalShots} ride along purely so {@link ShotAssetBatchExecutor#label}
 * can produce a friendly "Shot 4 of 10 -- camera plan" string without job-lifecycle-common's
 * generic {@code BatchWorker} ever needing to know what a "shot" is. */
public record ShotAssetStep(UUID shotId, int shotNumber, int totalShots, Kind kind) {

    public enum Kind { LIGHTING_PLAN, CAMERA_PLAN, STORYBOARD_IMAGE, PRODUCTION_IMAGE, LIGHTING_IMAGE, CAMERA_PLAN_IMAGE }
}
