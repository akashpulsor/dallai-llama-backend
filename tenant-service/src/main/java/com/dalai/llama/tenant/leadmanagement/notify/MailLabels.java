package com.dalai.llama.tenant.leadmanagement.notify;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** How mail words the showcase codes; the same wording the brands site uses (platform-ui labels.js). */
public final class MailLabels {

    private static final Map<ShowcaseIndustry, String> INDUSTRY = Map.ofEntries(
            Map.entry(ShowcaseIndustry.FASHION, "Fashion"), Map.entry(ShowcaseIndustry.BEAUTY, "Beauty"),
            Map.entry(ShowcaseIndustry.FOOD_BEVERAGE, "Food & beverage"), Map.entry(ShowcaseIndustry.TECH, "Tech"),
            Map.entry(ShowcaseIndustry.FINANCE, "Finance"), Map.entry(ShowcaseIndustry.REAL_ESTATE, "Real estate"),
            Map.entry(ShowcaseIndustry.EDUCATION, "Education"), Map.entry(ShowcaseIndustry.HEALTH, "Health"),
            Map.entry(ShowcaseIndustry.TRAVEL, "Travel"), Map.entry(ShowcaseIndustry.AUTOMOTIVE, "Automotive"),
            Map.entry(ShowcaseIndustry.ECOMMERCE, "E-commerce"), Map.entry(ShowcaseIndustry.ENTERTAINMENT, "Entertainment"),
            Map.entry(ShowcaseIndustry.OTHER, "Other"));

    private static final Map<ShowcaseFormat, String> FORMAT = Map.of(
            ShowcaseFormat.PRODUCT_AD, "Product ad", ShowcaseFormat.UGC, "UGC", ShowcaseFormat.EXPLAINER, "Explainer",
            ShowcaseFormat.BRAND_FILM, "Brand film", ShowcaseFormat.SOCIAL_SHORT, "Social short");

    private MailLabels() {
    }

    /** Null for none and for OTHER, which says nothing to a brand. */
    public static String industry(ShowcaseIndustry industry) {
        return industry == null || industry == ShowcaseIndustry.OTHER ? null : INDUSTRY.get(industry);
    }

    public static String format(ShowcaseFormat format) {
        return format == null ? null : FORMAT.get(format);
    }

    /** 59.7 -> "1:00", 125 -> "2:05"; null for an unknown length. */
    public static String duration(BigDecimal seconds) {
        if (seconds == null || seconds.signum() <= 0) return null;
        int total = seconds.setScale(0, RoundingMode.HALF_UP).intValue();
        return total / 60 + ":" + String.format("%02d", total % 60);
    }
}
