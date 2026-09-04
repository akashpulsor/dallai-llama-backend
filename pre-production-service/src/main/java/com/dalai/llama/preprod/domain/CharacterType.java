package com.dalai.llama.preprod.domain;

/** Whether a narrative {@link com.dalai.llama.preprod.domain.entity.ScriptCharacter} is a human
 * performer, a product the ad exists to showcase, or a narrator -- set by script generation (or a
 * manual edit), and what determines whether a HUMAN-, PRODUCT-, or NARRATOR-typed
 * {@link com.dalai.llama.preprod.domain.entity.CastProfile} is the right fit when the creator
 * assigns one via {@code CastAssignment}.
 *
 * <p>NARRATOR is distinct from a HUMAN character who happens to speak voice-over in a shot: a
 * narrator is never on screen and never appears in a scene's cast list (see
 * {@code screenplay_scene_character}) -- it exists purely to carry the narration voice, the same
 * "who's actually talking in this beat" distinction a real video director makes between a
 * character's line and a narrator's line. */
public enum CharacterType {
    HUMAN,
    PRODUCT,
    NARRATOR
}
