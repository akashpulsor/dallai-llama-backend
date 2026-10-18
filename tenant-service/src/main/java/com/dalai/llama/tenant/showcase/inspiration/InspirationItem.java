package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.util.UUID;

/**
 * A Pinterest video ops picked as a reference for brands in an industry (V47).
 *
 * @param pinUrl       the pin on Pinterest, which the embed credits and links to
 * @param embedHeight  the player's height at Pinterest's 450px width (V50), to size the card; null when unknown
 */
public record InspirationItem(UUID id, String pinId, String pinUrl, String title, ShowcaseIndustry industry, String note,
                              Integer embedHeight) {
}
