package com.dalai.llama.product.dto.creatorvideo;

/** The full, exact entitlement set for the creator-video product -- no numeric caps/seats yet
 * (product decision: add a per-entitlement seat count later if a real limit is needed, not now). */
public record CreatorVideoEntitlements(
        boolean videoCreationEnabled,
        boolean videoDownloadEnabled,
        boolean editsEnabled,
        boolean imageUploadEnabled,
        boolean upscalingEnabled,
        boolean upscalePreviewEnabled,
        boolean characterVoiceUploadEnabled,
        boolean briefUrlShareEnabled
) {

    /** What a tenant with no creator-video subscription row at all gets -- same shape the FREE
     * plan's DB row resolves to, kept here as a code-level fallback so a missing/unseeded FREE
     * plan can never turn into "no entitlements resolve, request fails" for a brand new tenant. */
    public static CreatorVideoEntitlements freeDefaults() {
        return new CreatorVideoEntitlements(true, true, false, false, false, false, false, false);
    }
}
