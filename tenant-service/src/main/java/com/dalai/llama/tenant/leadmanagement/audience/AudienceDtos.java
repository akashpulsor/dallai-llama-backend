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

    public record ImportReport(UUID batchId, int rowsTotal, int rowsImported, int rowsRejected, int leadsCreated, int leadsMerged,
                               int contactPointsAdded, int audienceSize) {
    }

    /** A contact point as the creator sees it. {@code status} is VERIFIED, LIKELY_VALID or UNVERIFIED
     * (INVALID ones are only counted, see {@link LeadView#discarded}). */
    public record ContactPointView(UUID id, String value, String status, boolean unsubscribed) {
    }

    /** Rule 25: per kind, validated contact points if there are any, else the unvalidated ones. */
    public record LeadView(UUID id, String name, String company, ShowcaseIndustry industry, String website,
                           List<ContactPointView> emails, List<ContactPointView> phones, int discarded, boolean reachable) {
    }

    public record LeadPage(List<LeadView> leads, int page, int size, int total) {
    }
}
