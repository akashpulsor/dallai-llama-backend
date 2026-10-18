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
                .ifPresent(to -> mailer.send(CreatorRequestMail.render(to, copy, brand, inquiry, brandProperties.creatorRequestsUrl())));
    }
}
