package com.dalai.llama.tenant.showcase.domain;

/** The one fixed industry list used everywhere a brand type matters: a creator's industries, a
 * showcase item's industry, a brand's industry and the Discover filter (design §3 row 7). The
 * frontend owns the display labels; the API carries these codes. */
public enum ShowcaseIndustry {
    FASHION,
    BEAUTY,
    FOOD_BEVERAGE,
    TECH,
    FINANCE,
    REAL_ESTATE,
    EDUCATION,
    HEALTH,
    TRAVEL,
    AUTOMOTIVE,
    ECOMMERCE,
    ENTERTAINMENT,
    OTHER
}
