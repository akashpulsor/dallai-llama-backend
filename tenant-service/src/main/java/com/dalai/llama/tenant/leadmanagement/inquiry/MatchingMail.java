package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import org.springframework.web.util.HtmlUtils;

/** What the brand hears after paying a request Dalaillama holds: we're assigning the creator, then
 * who it is. Never "no creator": the founder makes any video nobody else takes. */
final class MatchingMail {

    private MatchingMail() {
    }

    static PlatformMailer.Mail assigning(BrandContact brand, String span, String link) {
        return mail(brand, "Payment received: assigning your creator",
                "Thanks, your payment is in. We're assigning your creator now, usually within " + span
                        + ", and we'll mail you who is making your video. Your brief is here meanwhile:", link, "Open your brief");
    }

    static PlatformMailer.Mail assigned(BrandContact brand, String creatorName, String link) {
        return mail(brand, "Your creator: " + creatorName,
                creatorName + " is making your video and starts from your brief. Production updates and the review link come to "
                        + "this email.", link, "Open your brief");
    }

    private static PlatformMailer.Mail mail(BrandContact brand, String subject, String lead, String link, String button) {
        String hello = brand.getContactName() == null ? "Hi," : "Hi " + brand.getContactName() + ",";
        String text = hello + "\n\n" + lead + "\n\n" + link + "\n";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p><p>" + HtmlUtils.htmlEscape(lead) + "</p><p><a href=\""
                + HtmlUtils.htmlEscape(link) + "\">" + HtmlUtils.htmlEscape(button) + "</a></p>";
        return new PlatformMailer.Mail(brand.getEmail(), subject, text, html);
    }
}
