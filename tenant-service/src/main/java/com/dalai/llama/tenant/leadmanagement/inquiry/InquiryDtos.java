package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Requests and views for brand requests ("Request a video"). */
public final class InquiryDtos {

    private InquiryDtos() {
    }

    public record SubmitInquiryRequest(
            /* The film they were watching, if any. */
            @Size(max = 12) String publicId,
            /* The tracked mail link they came through, if any. */
            @Size(max = 32) String attributionToken,
            BrandInquiry.BudgetBand budgetBand,
            BrandInquiry.Timeline timeline,
            @NotBlank @Size(max = 1000) String message,
            @Size(max = 160) String productName,
            @Size(max = 1000) String productDescription,
            @Size(max = 500) String targetAudience,
            @Size(max = 1000) String campaignDirection,
            @jakarta.validation.constraints.Min(5) @jakarta.validation.constraints.Max(600) Integer durationSeconds,
            @Size(max = 6) java.util.List<@Size(max = 30) String> languages
    ) {
    }

    /** The brief details a brand filled in (rule 48); null fields were left empty. */
    public record BriefDetails(String productName, String productDescription, String targetAudience, String campaignDirection,
                               Integer durationSeconds, java.util.List<String> languages) {
        public static BriefDetails of(BrandInquiry i) {
            return new BriefDetails(i.getProductName(), i.getProductDescription(), i.getTargetAudience(), i.getCampaignDirection(),
                    i.getDurationSeconds(), i.getLanguages() == null || i.getLanguages().isBlank() ? java.util.List.of()
                            : java.util.List.of(i.getLanguages().split(",")));
        }
    }

    public record SubmittedInquiry(UUID id, BrandInquiry.Status status) {
    }

    /** The brand's view of one of their requests. */
    public record BrandInquiryView(
            UUID id,
            String creatorHandle,
            String creatorName,
            String creatorAvatarUrl,
            String filmTitle,
            BrandInquiry.BudgetBand budgetBand,
            BrandInquiry.Timeline timeline,
            String message,
            BrandInquiry.Status status,
            /* Set once the creator turned it into a brief. */
            String briefUrl,
            OffsetDateTime createdAt,
            BriefDetails brief
    ) {
    }

    /** The creator's view: who asked, about what, and how to reach them. */
    public record CreatorInquiryView(
            UUID id,
            Brand brand,
            String filmPublicId,
            String filmTitle,
            BrandInquiry.BudgetBand budgetBand,
            BrandInquiry.Timeline timeline,
            String message,
            BrandInquiry.Status status,
            boolean cameFromMail,
            String briefUrl,
            OffsetDateTime createdAt,
            BriefDetails brief
    ) {
        public record Brand(String contactName, String companyName, String email, String websiteUrl, ShowcaseIndustry industry) {
        }
    }

    public record StatusRequest(@NotNull BrandInquiry.Status status) {
    }
}
