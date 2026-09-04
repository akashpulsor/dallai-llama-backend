package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CharacterType;

import java.util.UUID;

/** A character resolved onto a screenplay scene via the real {@code screenplay_scene_character}
 * join -- just enough to render a tag ("who's in this scene") without a second round-trip. */
public record SceneCharacterView(
        UUID scriptCharacterId,
        String characterKey,
        String characterName,
        CharacterType characterType
) {
}
