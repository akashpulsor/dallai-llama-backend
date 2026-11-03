package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mails a creator about a brand's request ({@link CreatorRequestMail}). A request becomes a brief
 * the brand pays at once, so the creator hears about it when it is paid; before that only when
 * they have to act: accept an offer, or make the brief by hand when it couldn't be made for them.
 */
@Component
@RequiredArgsConstructor
public class CreatorRequestNotifier {

    private final TenantRepository tenantRepository;
    private final PlatformMailer mailer;
    private final BrandProperties brandProperties;

    /** The brand paid the brief made from this request: production can start. */
    public void paid(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry, String amountPaid) {
        String who = CreatorRequestMail.brandName(brand);
        send(creatorTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                "Paid: " + who + " wants a video from you",
                who + " paid for their video",
                "Their brief is funded, so you can start. Open it for the details, images and the brand's notes.",
                amountPaid + " paid upfront", "Open the brief"));
    }

    /** Offered to several creators: the first to accept takes it. */
    public void offered(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry) {
        String who = CreatorRequestMail.brandName(brand);
        send(creatorTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                who + " is looking for a creator",
                "New request: " + who,
                "It's offered to a few creators; the first to accept takes it, and the brand gets the brief to pay.",
                null, "See it and accept"));
    }

    /** Paid already and offered to a few creators for the matching window: the first to accept takes it. */
    public void offeredPaid(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry, java.time.Duration window) {
        String who = CreatorRequestMail.brandName(brand);
        send(creatorTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                "Paid: " + who + " needs a creator now",
                who + " has paid and is waiting for a creator",
                "The brand paid upfront. It's offered to a few creators for the next " + span(window)
                        + "; the first to accept takes it, brief and payment included.",
                "Already paid upfront", "See it and accept"));
    }

    /** They took a paid request from the matching (or ops gave it to them): it's theirs, start. */
    public void matched(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry) {
        String who = CreatorRequestMail.brandName(brand);
        send(creatorTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                "It's yours: " + who + "'s video",
                who + "'s video is yours",
                "The brand has paid upfront, so you can start. Open the brief for the details, images and the brand's notes.",
                "Already paid upfront", "Open the brief"));
    }

    /** Nobody took a paid request in time: Dalaillama's own account (the founder) makes it. */
    public void keptForHouse(UUID houseTenantId, BrandContact brand, BrandInquiry inquiry) {
        String who = CreatorRequestMail.brandName(brand);
        send(houseTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                "No creator took it: " + who + "'s video is yours",
                who + "'s paid video is yours to make",
                "No creator accepted it in time, so it stays with you. The brand has paid upfront and has been told you're making it.",
                "Already paid upfront", "Open the brief"));
    }

    /** "2 hours", "90 minutes". */
    public static String span(java.time.Duration d) {
        long minutes = d.toMinutes();
        if (minutes % 60 == 0) return minutes / 60 == 1 ? "hour" : minutes / 60 + " hours";
        return minutes + " minutes";
    }

    /** Theirs, but the brief couldn't be made automatically: they make it from their requests. */
    public void needsBrief(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry) {
        String who = CreatorRequestMail.brandName(brand);
        send(creatorTenantId, brand, inquiry, new CreatorRequestMail.Copy(
                who + " asked you for a video",
                "New request: " + who,
                "Turn it into a brief from your requests; the brand gets it to review and pay.",
                null, "Make the brief"));
    }

    private void send(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry, CreatorRequestMail.Copy copy) {
        tenantRepository.findById(creatorTenantId).map(Tenant::getPrimaryContactEmail)
                .ifPresent(to -> mailer.sendAfterCommit(CreatorRequestMail.render(to, copy, brand, inquiry, brandProperties.creatorRequestsUrl())));
    }
}
