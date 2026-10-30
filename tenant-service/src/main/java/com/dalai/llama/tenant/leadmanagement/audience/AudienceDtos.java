package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Requests and views of the creator's audience endpoints. */
public final class AudienceDtos {

    private AudienceDtos() {
    }

    public record CreateAudienceRequest(@NotBlank @Size(max = 80) String name) {
    }

    public record AudienceView(UUID id, String name, Instant createdAt, int leads, int reachable) {
    }

    /** {@code leadsWithoutEmail}: leads this upload touched that have no usable email (kept; the creator adds one). */
    public record ImportReport(UUID batchId, int rowsTotal, int rowsImported, int rowsRejected, int leadsCreated, int leadsMerged,
                               int contactPointsAdded, int audienceSize, int leadsWithoutEmail) {
    }

    /** A contact point as the creator sees it. {@code status} is VERIFIED, LIKELY_VALID or UNVERIFIED
     * (INVALID ones are only counted, see {@link LeadView#discarded}). */
    public record ContactPointView(UUID id, String value, String status, boolean unsubscribed) {
    }

    /** Rule 25: per kind, validated contact points if there are any, else the unvalidated ones. */
    public record LeadView(UUID id, String name, String designation, String company, ShowcaseIndustry industry, String website,
                           CompanySize size, List<ContactPointView> emails, List<ContactPointView> phones, int discarded, boolean reachable) {
    }

    /** One lead typed in by the creator: the same fields as the CSV columns. */
    public record AddLeadRequest(
            @jakarta.validation.constraints.Size(max = 120) String name,
            @jakarta.validation.constraints.Size(max = 120) String designation,
            @jakarta.validation.constraints.Size(max = 160) String company,
            @jakarta.validation.constraints.Size(max = 600) String email,
            @jakarta.validation.constraints.Size(max = 200) String phone,
            ShowcaseIndustry industry,
            @jakarta.validation.constraints.Size(max = 255) String website,
            CompanySize size) {
    }

    /** {@code merged}: it matched a lead the creator already had (same email/phone, or same company). */
    public record AddLeadResult(UUID leadId, boolean merged, int audienceSize) {
    }

    public record LeadPage(List<LeadView> leads, int page, int size, int total) {
    }
}
