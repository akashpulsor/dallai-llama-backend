package com.dalai.llama.preprod.domain;

/** Closed vocabulary that {@code ShotContextAssemblyStrategy} routes on -- one strategy bean per
 * type, no if/switch at the call site (see ShotContextAssemblyService). */
public enum ShotType {
    DIALOGUE,
    ACTION,
    PRODUCT_HERO,
    B_ROLL,
    TRANSITION
}
