package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/** "Your brief is ready": the link to review, edit and pay, with the price and what's due upfront
 * when the brief was priced. */
final class BriefReadyMail {

    private BriefReadyMail() {
    }

    static PlatformMailer.Mail of(BrandContact brand, String creatorName, String link, CreativePlanningBriefClient.CreatedBrief brief) {
        String hello = brand.getContactName() == null ? "Hi," : "Hi " + brand.getContactName() + ",";
        String terms = brief.totalPrice() == null || brief.requiredAmount() == null ? ""
                : "The video costs " + money(brief.totalPrice(), brief.currency()) + "; pay "
                + money(brief.requiredAmount(), brief.currency()) + " now and production starts. ";
        String lead = creatorName + " will make your video. Review the brief, change anything you like, and pay to start. " + terms;
        String text = hello + "\n\n" + lead + "\n\n" + link + "\n";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p><p>" + HtmlUtils.htmlEscape(lead) + "</p><p><a href=\""
                + HtmlUtils.htmlEscape(link) + "\">Review the brief and pay</a></p>";
        return new PlatformMailer.Mail(brand.getEmail(), "Your video brief is ready", text, html);
    }

    static String money(BigDecimal amount, String currency) {
        // Whole rupees as "4,999"; anything else with both paise digits, "1,499.70".
        boolean whole = amount.stripTrailingZeros().scale() <= 0;
        String number = new DecimalFormat(whole ? "#,##0" : "#,##0.00").format(amount);
        return currency == null || "INR".equals(currency) ? "₹" + number : currency + " " + number;
    }
}
