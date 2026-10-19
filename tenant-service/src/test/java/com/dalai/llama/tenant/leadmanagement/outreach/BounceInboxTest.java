package com.dalai.llama.tenant.leadmanagement.outreach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Only failures that are about the address itself suppress it. */
class BounceInboxTest {

    @Test
    void noSuchMailboxAndHostingersOwnBlockAreHardBounces() {
        String report = """
                Content-Type: message/delivery-status

                Reporting-MTA: dns; fr-int-smtpout14.hostinger.io

                Final-Recipient: rfc822; Asha@Hearth.example
                Original-Recipient: rfc822;asha@hearth.example
                Action: failed
                Status: 5.1.1
                Diagnostic-Code: smtp; 550 5.1.1 The email account that you tried to reach does not exist.

                Final-Recipient: rfc822; test@gmail.com
                Action: failed
                Status: 5.7.1
                Diagnostic-Code: smtp; 550 5.7.1 [RB] This recipient email address has been blocked
                """;

        assertThat(BounceInbox.hardBounces(report)).containsExactly("asha@hearth.example", "test@gmail.com");
    }

    @Test
    void policyRejectionsAndDelaysAreNotAboutTheAddress() {
        String report = """
                Final-Recipient: rfc822; buyer@brand.example
                Action: failed
                Status: 5.7.1
                Diagnostic-Code: smtp; 550 5.7.1 Message rejected due to sender reputation

                Final-Recipient: rfc822; slow@brand.example
                Action: delayed
                Status: 4.4.1
                """;

        assertThat(BounceInbox.hardBounces(report)).isEmpty();
    }
}
