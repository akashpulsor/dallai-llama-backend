package com.dalai.llama.tenant.leadmanagement.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailRendererTest {

    private final MailRenderer renderer = new MailRenderer();

    record Text(String text) {
    }

    record Wrong(String title) {
    }

    @Test
    void htmlEscapesValuesButTextDoesNot() {
        Text text = new Text("Ghee <launch> & \"more\"");
        assertThat(renderer.html("p", text)).contains("Ghee &lt;launch&gt; &amp; &quot;more&quot;").doesNotContain("<launch>");
        assertThat(renderer.text("p", text)).contains("Ghee <launch> & \"more\"");
    }

    @Test
    void aPlaceholderTheContextLacksFailsInsteadOfShippingBlank() {
        assertThatThrownBy(() -> renderer.html("film", new Wrong("x"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in Wrong");
    }
}
