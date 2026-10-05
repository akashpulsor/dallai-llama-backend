package com.dalai.llama.preprod.service.continuity;

/** Where a visual value came from. {@link #rank} is the step-shot authority order (lower wins);
 * SCREENPLAY and SHOT_DESCRIPTION rank 2 only as an explicit, evidenced transition -- as plain
 * shot requirements they rank 4, which the resolver applies through {@link ResolutionSeverity}. */
public enum ContinuitySource {
    USER_OVERRIDE(1, "Your choice"),
    SCREENPLAY(2, "Screenplay"),
    SHOT_DESCRIPTION(2, "Shot description"),
    REFERENCE_IMAGE(3, "Reference image"),
    PROJECT_LOOK(5, "Project look"),
    SHOT_METADATA(6, "Shot metadata"),
    LIGHTING_PLAN(7, "Lighting plan"),
    SYSTEM_INFERENCE(8, "System inference");

    private final int rank;
    private final String label;

    ContinuitySource(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }
}
