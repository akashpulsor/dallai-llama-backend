package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.util.UUID;

/**
 * A video ops picked as a reference for brands in an industry (V47), shown tagged "Inspiration":
 * an idea of what a creator can make, never presented as a creator's own work.
 *
 * @param embedHeight   the old embed's height (V50); kept for the board's layout until the picture is known
 * @param posterUrl     its picture, and {@code videoUrl} its file (V56, V57), to play in our own player;
 *                      null until filled from the pin's info
 * @param makerName     who made it, credited on the card
 */
public record InspirationItem(UUID id, String pinId, String pinUrl, String title, ShowcaseIndustry industry, String note,
                              Integer embedHeight, String posterUrl, Integer posterWidth, Integer posterHeight, String videoUrl,
                              java.math.BigDecimal durationSeconds, String makerName) {
}
