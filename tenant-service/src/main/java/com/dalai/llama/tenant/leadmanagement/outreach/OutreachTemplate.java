package com.dalai.llama.tenant.leadmanagement.outreach;

/** What a mail says. The creator's three are sent from their own address; NEW_FILM (a creator you
 * follow posted a film) only ever travels inside the daily digest. */
public enum OutreachTemplate {
    /** "Here's a film I made" — one film. */
    SHOWCASE_WORK(true, 1),
    /** "I made this for a brand like yours" — one film from the recipient's industry. */
    SIMILAR_BRAND_WORK(true, 1),
    /** "Some of my work" — up to three films and the profile. */
    CREATOR_PORTFOLIO(true, 3),
    NEW_FILM(false, 1);

    private final boolean creatorSendable;
    private final int maxFilms;

    OutreachTemplate(boolean creatorSendable, int maxFilms) {
        this.creatorSendable = creatorSendable;
        this.maxFilms = maxFilms;
    }

    public boolean creatorSendable() {
        return creatorSendable;
    }

    public int maxFilms() {
        return maxFilms;
    }
}
