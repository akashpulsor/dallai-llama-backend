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

/** Requests and views of the creator's outreach endpoints. */
public final class OutreachDtos {

    private OutreachDtos() {
    }

    public record Recipient(@NotBlank @Email @Size(max = 254) String email, @Size(max = 80) String name,
                            @Size(max = 120) String company) {
    }

    public record SendRequest(
            @NotNull OutreachTemplate template,
            /* Required except for CREATOR_PORTFOLIO, which uses the creator's best films. */
            @Size(max = 12) String publicId,
            @Size(max = 500) String note,
            @NotEmpty @Valid List<Recipient> recipients
    ) {
    }

    public record PreviewRequest(@NotNull OutreachTemplate template, @Size(max = 12) String publicId,
                                 @Size(max = 500) String note, @Size(max = 80) String recipientName) {
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
                               List<MailableFilmView> mailableFilms, int maxRecipientsPerSend, int cooldownDays) {
    }

    public record ReachView(int mailsDelivered30d, int clicks30d, int requestsFromMail30d, int followers) {
    }

    public record BuyPackRequest(@NotBlank @Size(max = 64) String idempotencyKey) {
    }

    public record PackBought(int mails, java.math.BigDecimal price, String currency, MailAllowance.AllowanceView allowance) {
    }
}
