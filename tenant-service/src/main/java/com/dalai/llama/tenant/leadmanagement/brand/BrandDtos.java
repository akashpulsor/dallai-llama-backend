package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Requests and views of the brand-facing endpoints. */
public final class BrandDtos {

    private BrandDtos() {
    }

    /** Sign-up and sign-in are one form: an unknown email becomes a brand, a known one just gets a
     * link. {@code pendingAction} is handed back after sign-in so the page can finish what the brand
     * started (e.g. {@code follow:riya-motion}). */
    public record SignInRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 80) String contactName,
            @Size(max = 120) String companyName,
            @Size(max = 255) @Pattern(regexp = "^$|^https?://\\S+$", message = "must be an http(s) link") String websiteUrl,
            ShowcaseIndustry industry,
            @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "must be a two-letter country code") String countryCode,
            boolean autoPicksOptIn,
            @Size(max = 255) @Pattern(regexp = "^[A-Za-z0-9:_-]*$", message = "invalid") String pendingAction
    ) {
    }

    /** What a completed sign-in tells the page. The session itself travels only in the HttpOnly
     * cookie, never in a body a script could read. */
    public record SignedIn(Instant expiresAt, String pendingAction, BrandMeView brand) {
    }

    public record FollowedCreator(String handle, String displayName, String avatarUrl, String headline) {
    }

    public record BrandMeView(
            String email,
            String contactName,
            String companyName,
            String websiteUrl,
            ShowcaseIndustry industry,
            String countryCode,
            boolean autoPicksOptIn,
            int autoCadenceDays,
            List<FollowedCreator> following
    ) {
    }

    public record PreferencesRequest(
            boolean autoPicksOptIn,
            @Min(7) @Max(60) int autoCadenceDays,
            ShowcaseIndustry industry
    ) {
    }
}
