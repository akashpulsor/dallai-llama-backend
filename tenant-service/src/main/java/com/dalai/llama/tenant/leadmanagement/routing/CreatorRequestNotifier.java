package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Mails a creator that a request is theirs, or offered to them. */
@Component
@RequiredArgsConstructor
public class CreatorRequestNotifier {

    private final TenantRepository tenantRepository;
    private final PlatformMailer mailer;
    private final BrandProperties brandProperties;

    public void assigned(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry) {
        send(creatorTenantId, brand, inquiry, " asked you for a video", " sent you a request on Dalai Llama:", "See it and reply: ");
    }

    public void offered(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry) {
        send(creatorTenantId, brand, inquiry, " is looking for a creator", " asked Dalai Llama for a video:",
                "It's offered to a few creators; the first to accept takes it: ");
    }

    private void send(UUID creatorTenantId, BrandContact brand, BrandInquiry inquiry, String subject, String lead, String action) {
        Optional<String> to = tenantRepository.findById(creatorTenantId).map(Tenant::getPrimaryContactEmail);
        if (to.isEmpty()) return;
        String who = firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail());
        mailer.send(new PlatformMailer.Mail(to.get(), who + subject,
                who + lead + "\n\n\"" + inquiry.getMessage() + "\"\n\n" + action + brandProperties.creatorRequestsUrl() + "\n",
                null));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
