package com.dalai.llama.creator.domain;

/**
 * Discriminates the rows creator_assets carries for the screenplay video workflow.
 * Kept small on purpose - creator_assets.asset_type is a free-form column shared with
 * unrelated asset kinds (avatars, reference images, generated audio), so this enum only
 * enumerates the values this workflow itself writes and queries.
 */
public enum ScreenplaySceneAssetType {
    SCENE_VIDEO,
    COMBINED_VIDEO
}
