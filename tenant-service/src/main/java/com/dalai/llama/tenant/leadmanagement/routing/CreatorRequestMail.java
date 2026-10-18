package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one layout every request mail to a creator uses: a header, the brand's request as a card,
 * an optional highlight (what was paid) and one button. Tables and inline styles, because mail
 * clients drop stylesheets; a plain-text part carries the same content.
 */
final class CreatorRequestMail {

    private static final String PURPLE = "#7c3aed";
    private static final String INK = "#0f172a";
    private static final String MUTED = "#64748b";

    private CreatorRequestMail() {
    }

    /** What the mail says around the request. {@code highlight} may be null. */
    record Copy(String subject, String headline, String lead, String highlight, String button) {
    }

    static PlatformMailer.Mail render(String to, Copy copy, BrandContact brand, BrandInquiry inquiry, String buttonUrl) {
        List<String[]> rows = details(brand, inquiry);
        return new PlatformMailer.Mail(to, copy.subject(), text(copy, rows, inquiry, buttonUrl), html(copy, rows, inquiry, buttonUrl));
    }

    static String brandName(BrandContact brand) {
        for (String v : new String[]{brand.getCompanyName(), brand.getContactName(), brand.getEmail()}) {
            if (v != null && !v.isBlank()) return v;
        }
        return "A brand";
    }

    private static List<String[]> details(BrandContact brand, BrandInquiry inquiry) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Brand", brandName(brand)});
        if (inquiry.getIndustry() != null) rows.add(new String[]{"Industry", words(inquiry.getIndustry().name())});
        if (inquiry.getProductName() != null) rows.add(new String[]{"Product", inquiry.getProductName()});
        if (inquiry.getDurationSeconds() != null) rows.add(new String[]{"Length", inquiry.getDurationSeconds() + " seconds"});
        if (inquiry.getLanguages() != null) rows.add(new String[]{"Languages", inquiry.getLanguages().replace(",", ", ")});
        if (inquiry.getTimeline() != null) rows.add(new String[]{"When", words(inquiry.getTimeline().name())});
        return rows;
    }

    /** FOOD_BEVERAGE -> "Food beverage". */
    private static String words(String code) {
        String s = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String text(Copy copy, List<String[]> rows, BrandInquiry inquiry, String url) {
        StringBuilder t = new StringBuilder(copy.headline()).append("\n\n").append(copy.lead()).append("\n\n");
        if (copy.highlight() != null) t.append(copy.highlight()).append("\n\n");
        for (String[] row : rows) t.append(row[0]).append(": ").append(row[1]).append('\n');
        t.append("\n\"").append(inquiry.getMessage()).append("\"\n\n").append(copy.button()).append(": ").append(url).append('\n');
        return t.toString();
    }

    private static String html(Copy copy, List<String[]> rows, BrandInquiry inquiry, String url) {
        StringBuilder details = new StringBuilder();
        for (String[] row : rows) {
            details.append("<tr><td style=\"padding:6px 0;color:").append(MUTED).append(";font-size:13px;width:110px\">")
                    .append(esc(row[0])).append("</td><td style=\"padding:6px 0;color:").append(INK)
                    .append(";font-size:14px;font-weight:600\">").append(esc(row[1])).append("</td></tr>");
        }
        String highlight = copy.highlight() == null ? "" : "<tr><td style=\"padding:0 32px 8px\"><div style=\"background:#ecfdf5;"
                + "border:1px solid #a7f3d0;border-radius:10px;padding:12px 16px;color:#065f46;font-size:15px;font-weight:700\">"
                + esc(copy.highlight()) + "</div></td></tr>";
        return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f1f5f9\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f1f5f9;padding:24px 12px\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;"
                + "border-radius:16px;overflow:hidden;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif\">"
                + "<tr><td style=\"background:" + PURPLE + ";padding:20px 32px;color:#ffffff;font-size:15px;font-weight:800;letter-spacing:.3px\">"
                + "Dalaillama</td></tr>"
                + "<tr><td style=\"padding:28px 32px 8px\"><h1 style=\"margin:0 0 8px;color:" + INK + ";font-size:22px;line-height:1.3\">"
                + esc(copy.headline()) + "</h1><p style=\"margin:0 0 16px;color:#334155;font-size:15px;line-height:1.5\">" + esc(copy.lead())
                + "</p></td></tr>"
                + highlight
                + "<tr><td style=\"padding:8px 32px\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border:1px solid #e2e8f0;border-radius:12px;padding:14px 18px\">" + details
                + "<tr><td colspan=\"2\" style=\"padding:12px 0 2px;color:#334155;font-size:14px;line-height:1.55;font-style:italic\">"
                + "&ldquo;" + esc(inquiry.getMessage()) + "&rdquo;</td></tr></table></td></tr>"
                + "<tr><td style=\"padding:20px 32px 32px\"><a href=\"" + esc(url) + "\" style=\"display:inline-block;background:" + PURPLE
                + ";color:#ffffff;text-decoration:none;font-weight:700;font-size:15px;padding:12px 22px;border-radius:10px\">"
                + esc(copy.button()) + "</a></td></tr>"
                + "</table><p style=\"color:" + MUTED + ";font-size:12px;margin:14px 0 0;font-family:Arial,sans-serif\">"
                + "You get this because you take requests on Dalaillama.</p></td></tr></table></body></html>";
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }
}
