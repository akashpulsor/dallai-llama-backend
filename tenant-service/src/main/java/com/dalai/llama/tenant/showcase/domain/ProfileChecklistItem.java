package com.dalai.llama.tenant.showcase.domain;

/** Something a creator still has to add before their profile counts as complete (design §3,
 * row 1b). */
public enum ProfileChecklistItem {
    YOUTUBE_CHANNEL,
    /** Fewer live showcase videos than {@code showcase.picks.min-initial-picks}. */
    SHOWCASE_PICKS,
    AVATAR,
    HEADLINE,
    INDUSTRY
}
