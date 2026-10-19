package com.dalai.llama.product.dto.creatorvideo;

/** The full, exact entitlement set for the creator-video product: feature switches plus the two
 * outreach caps -- emails to brands a month, and how many brands the creator's work reaches
 * (null = every brand). */
public record CreatorVideoEntitlements(
        boolean videoCreationEnabled,
        boolean videoDownloadEnabled,
        boolean editsEnabled,
        boolean imageUploadEnabled,
        boolean upscalingEnabled,
        boolean upscalePreviewEnabled,
        boolean characterVoiceUploadEnabled,
        boolean briefUrlShareEnabled,
        int monthlyOutreachEmails,
        Integer brandReach
) {
    public static final int FREE_MONTHLY_OUTREACH_EMAILS = 100;
    public static final int FREE_BRAND_REACH = 10;

    public static CreatorVideoEntitlements of(com.dalai.llama.product.domain.entity.CreatorVideoPlanEntitlement e) {
        return new CreatorVideoEntitlements(e.isVideoCreationEnabled(), e.isVideoDownloadEnabled(), e.isEditsEnabled(),
                e.isImageUploadEnabled(), e.isUpscalingEnabled(), e.isUpscalePreviewEnabled(),
                e.isCharacterVoiceUploadEnabled(), e.isBriefUrlShareEnabled(), e.getMonthlyOutreachEmails(), e.getBrandReach());
    }


    /** What a tenant with no creator-video subscription row at all gets -- same shape the FREE
     * plan's DB row resolves to, kept here as a code-level fallback so a missing/unseeded FREE
     * plan can never turn into "no entitlements resolve, request fails" for a brand new tenant. */
    public static CreatorVideoEntitlements freeDefaults() {
        return new CreatorVideoEntitlements(true, true, false, false, false, false, false, false,
                FREE_MONTHLY_OUTREACH_EMAILS, FREE_BRAND_REACH);
    }
}
