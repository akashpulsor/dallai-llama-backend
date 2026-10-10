package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Requests and views of the creator's outreach endpoints. */
public final class OutreachDtos {

    private OutreachDtos() {
    }

    public record Recipient(@NotBlank @Email @Size(max = 254) String email, @Size(max = 80) String name,
                            @Size(max = 120) String company) {
    }

    /** Name a template ({@code templateId}: global or the creator's own) or just a layout
     * ({@code template}: uses the built-in global template for it). */
    public record SendRequest(
            OutreachTemplate template,
            UUID templateId,
            /* Required except for CREATOR_PORTFOLIO, which uses the creator's best films. */
            @Size(max = 12) String publicId,
            @Size(max = 500) String note,
            @NotEmpty @Valid List<Recipient> recipients
    ) {
    }

    public record PreviewRequest(OutreachTemplate template, UUID templateId, @Size(max = 12) String publicId,
                                 @Size(max = 500) String note, @Size(max = 80) String recipientName) {
    }

    /** Rule 26: send to every lead in one of the creator's audiences, one email per lead. */
    public record AudienceSendRequest(
            @NotNull UUID audienceId,
            OutreachTemplate template,
            UUID templateId,
            @Size(max = 12) String publicId,
            @Size(max = 500) String note
    ) {
    }

    public record AudienceSendResult(int queued, int suppressed, int cooldown, int overAllowance, int duplicates,
                                     int noReachableContact, MailAllowance.AllowanceView allowance) {
    }

    public enum Outcome {
        /** Gone now, from the creator's address. */
        SENT,
        /** They already had an email today; it goes in their next daily digest. */
        QUEUED,
        /** This creator emailed them within the cooldown. */
        COOLDOWN,
        /** They unsubscribed (or bounced); never emailed again. */
        SUPPRESSED,
        /** No free mails or pack mails left. */
        OVER_ALLOWANCE,
        /** The address can't receive mail (bad syntax or no mail server); the mail was given back. */
        UNDELIVERABLE,
        DUPLICATE
    }

    public record RecipientResult(String email, Outcome outcome) {
    }

    public record SendResult(List<RecipientResult> results, MailAllowance.AllowanceView allowance) {
    }

    public record PreviewView(String subject, String text, String html) {
    }

    public record MailableFilmView(String publicId, String title, String thumbnailUrl, ShowcaseIndustry industry, String clientLabel) {
    }

    public record OverviewView(MailAllowance.AllowanceView allowance, List<BillingServiceClient.AddonOffer> packs,
                               List<MailableFilmView> mailableFilms, int maxRecipientsPerSend, int maxAudienceSend,
                               int cooldownDays) {
    }

    public record ReachView(int mailsDelivered30d, int clicks30d, int requestsFromMail30d, int followers) {
    }

    public record BuyPackRequest(@NotBlank @Size(max = 64) String idempotencyKey) {
    }

    public record PackBought(int mails, java.math.BigDecimal price, String currency, MailAllowance.AllowanceView allowance) {
    }
}
