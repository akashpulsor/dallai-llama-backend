package com.dalai.llama.tenant.leadmanagement.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** An open becomes a GA4 email_open event for the recipient (never their address). */
class OpenAnalyticsTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final OutreachStore.Opened open = new OutreachStore.Opened("CREATOR_MAIL", HASH, "SHOWCASE_WORK", true);

    @Test
    void withAnApiSecretItUsesTheMeasurementProtocol() throws Exception {
        HttpRequest request = new OpenAnalytics(new ObjectMapper(), "G-KCK0RY0YPP", "s3cret", "https://ga.example").request(open);

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://ga.example/mp/collect?measurement_id=G-KCK0RY0YPP&api_secret=s3cret");
    }

    @Test
    void withoutOneItIsACollectHit_keyedOnTheRecipientHashNotTheAddress() throws Exception {
        String url = new OpenAnalytics(new ObjectMapper(), "G-KCK0RY0YPP", "", "https://ga.example").request(open).uri().toString();

        assertThat(url).startsWith("https://ga.example/g/collect?v=2&tid=G-KCK0RY0YPP&cid=rcpt.0123456789abcdef&en=email_open_showcase_work")
                .contains("&ep.mail_kind=CREATOR_MAIL", "&ep.template=SHOWCASE_WORK", "&epn.first_open=1")
                .doesNotContain("@");
    }

    @Test
    void eachTemplateIsItsOwnEvent() {
        assertThat(OpenAnalytics.eventName(open)).isEqualTo("email_open_showcase_work");
        assertThat(OpenAnalytics.eventName(new OutreachStore.Opened("CREATOR_MAIL", HASH, "INSPIRATION", true))).isEqualTo("email_open_inspiration");
        assertThat(OpenAnalytics.eventName(new OutreachStore.Opened("DIGEST", HASH, "SHOWCASE_WORK", false))).isEqualTo("email_open_digest");
        assertThat(OpenAnalytics.eventName(new OutreachStore.Opened("DIGEST", HASH, null, false))).isEqualTo("email_open_digest");
    }

    @Test
    void noMeasurementIdSendsNothing() {
        // No exception, no request: analytics is simply off.
        new OpenAnalytics(new ObjectMapper(), "", "", "https://ga.example").opened(open);
    }
}
