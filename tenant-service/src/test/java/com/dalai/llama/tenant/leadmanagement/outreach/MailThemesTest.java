package com.dalai.llama.tenant.leadmanagement.outreach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Videos and brands are matched on what they're about, without false friends. */
class MailThemesTest {

    @Test
    void videoTitlesAndBrandsReadAsTheirProducts() {
        assertThat(MailThemes.of("Jewellery unboxing, lifestyle shots")).containsExactlyInAnyOrder("jewellery", "gifting");
        assertThat(MailThemes.of("Cat food ad, seven flavours in seven days")).contains("pet");
        assertThat(MailThemes.of("Bombay Perfumery bombayperfumery.com")).containsExactly("fragrance");
        assertThat(MailThemes.of("Botanic Love botaniclove.in")).containsExactly("skincare");
        assertThat(MailThemes.of("Muttley Crew muttleycrew.in Pet")).containsExactly("pet"); // the directory category
        assertThat(MailThemes.of("Footwear & travel")).contains("footwear", "travelgear");
        // A handbag brand gets bag videos.
        assertThat(MailThemes.of("handbags Zouk zouk.co.in")).contains("travelgear");
        assertThat(MailThemes.of("Leather bag product reel")).contains("travelgear");
    }

    @Test
    void shortWordsMatchWholeWordsOnly() {
        assertThat(MailThemes.of("Luxury collection coming-soon teaser")).doesNotContain("tea");
        assertThat(MailThemes.of("Womenswear summer trends reel")).containsExactly("womenswear");
        assertThat(MailThemes.of("Wellness retreat film, calm and slow")).containsExactly("wellness");
        assertThat(MailThemes.of("Waterfront home, privacy shown from the air")).doesNotContain("drinks");
        assertThat(MailThemes.of("Snitch snitch.co.in")).isEmpty();
    }

    @Test
    void overlapCountsSharedThemes() {
        assertThat(MailThemes.overlap(MailThemes.of("Pet hair remover demo"), MailThemes.of("Woof pet care"))).isEqualTo(1);
    }
}
