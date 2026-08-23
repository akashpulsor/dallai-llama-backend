package com.dalai.llama.preprod.domain;

/** Closed vocabulary that {@code ShotContextAssemblyStrategy} routes on -- one strategy bean per
 * type, no if/switch at the call site (see ShotContextAssemblyService). */
public enum ShotType {
    DIALOGUE,
    ACTION,
    PRODUCT_HERO,
    B_ROLL,
    TRANSITION,
    /** Text/data/graphic-driven beats (kinetic typography, lower-thirds, data callouts) -- planned
     * via {@code MotionGraphicPlanService} first (concept/on-screen text/visual style/animation
     * notes), then dispatched to video-generation-service like any other shot via {@code
     * MotionGraphicShotContextAssemblyStrategy}, which turns that plan into the prompt (and
     * attaches a reference image the same way PRODUCT_HERO does, when the shot's character key
     * resolves one). Dispatching before a plan exists fails loud with a clear error. */
    MOTION_GRAPHIC
}
