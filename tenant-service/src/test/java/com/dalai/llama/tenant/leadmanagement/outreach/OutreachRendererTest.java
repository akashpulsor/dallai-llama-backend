package com.dalai.llama.tenant.leadmanagement.outreach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutreachRendererTest {

    private final OutreachRenderer renderer = new OutreachRenderer();

    record Card(String title, String subtitle, String thumbnailUrl, String url) {
    }

    record Wrong(String title) {
    }

    @Test
    void htmlEscapesValuesButTextDoesNot() {
        Card card = new Card("Ghee <launch> & \"more\"", "Food", "https://i/x.jpg", "https://r/abc");
        assertThat(renderer.html("card", card)).contains("Ghee &lt;launch&gt; &amp; &quot;more&quot;").doesNotContain("<launch>");
        assertThat(renderer.text("card", card)).contains("* Ghee <launch> & \"more\" (Food)").contains("https://r/abc");
    }

    @Test
    void aPlaceholderTheContextLacksFailsInsteadOfShippingBlank() {
        assertThatThrownBy(() -> renderer.html("card", new Wrong("x"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in Wrong");
    }
}
