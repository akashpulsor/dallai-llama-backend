package com.dalai.llama.preprod.domain;

/** Whether a narrative {@link com.dalai.llama.preprod.domain.entity.ScriptCharacter} is a human
 * performer or a product the ad exists to showcase -- set by script generation (or a manual
 * edit), and what determines whether a HUMAN- or PRODUCT-typed
 * {@link com.dalai.llama.preprod.domain.entity.CastProfile} is the right fit when the creator
 * assigns one via {@code CastAssignment}. */
public enum CharacterType {
    HUMAN,
    PRODUCT
}
