package com.dalai.llama.tenant.leadmanagement.notify;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Request-triggered mail goes after the commit and off the request thread, never after a rollback;
 * it shows the brands alias on the outreach domain while the envelope stays the relay's mailbox. */
class PlatformMailerTest {

    private final JavaMailSender smtp = mock(JavaMailSender.class);
    private final List<Runnable> background = new ArrayList<>();
    private final PlatformMailer mailer = new PlatformMailer(smtp, new MailSigner("partner.dalaillama.in", "dl1", ""), background::add, "outreach@dalaillama.in", "",
            "brands@partner.dalaillama.in", true);
    private final PlatformMailer.Mail mail = new PlatformMailer.Mail("brand@x.example", "Your video brief is ready", "text", null);

    @BeforeEach
    void mime() {
        when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void insideATransactionItWaitsForTheCommitThenSendsInTheBackground() {
        TransactionSynchronizationManager.initSynchronization();
        mailer.sendAfterCommit(mail);
        assertThat(background).isEmpty();

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(background).hasSize(1);
        verify(smtp, never()).send(any(MimeMessage.class)); // not on the request thread

        background.get(0).run();
        verify(smtp, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void aRolledBackRequestSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        mailer.sendAfterCommit(mail);

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(background).isEmpty();
    }

    @Test
    void withoutATransactionItGoesStraightToTheBackground() {
        mailer.sendAfterCommit(mail);
        assertThat(background).hasSize(1);
        background.get(0).run();
        verify(smtp).send(any(MimeMessage.class));
    }

    @Test
    void itShowsTheBrandsAliasOnTheOutreachDomain_whileTheEnvelopeStaysTheMailbox() throws Exception {
        org.mockito.ArgumentCaptor<MimeMessage> sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        assertThat(mailer.send(mail)).isTrue();
        verify(smtp).send(sent.capture());
        assertThat(sent.getValue().getFrom()[0].toString()).isEqualTo("Dalai Llama <brands@partner.dalaillama.in>");
        assertThat(sent.getValue().getHeader("Sender")).isNull();
        assertThat(((org.eclipse.angus.mail.smtp.SMTPMessage) sent.getValue()).getEnvelopeFrom()).isEqualTo("outreach@dalaillama.in");
    }

    @Test
    void withNoAliasSetItFallsBackToTheMailbox() throws Exception {
        PlatformMailer plain = new PlatformMailer(smtp, new MailSigner("partner.dalaillama.in", "dl1", ""), background::add,
                "outreach@dalaillama.in", "", "", true);
        org.mockito.ArgumentCaptor<MimeMessage> sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        plain.send(mail);
        verify(smtp).send(sent.capture());
        assertThat(sent.getValue().getFrom()[0].toString()).isEqualTo("Dalai Llama <outreach@dalaillama.in>");
    }
}
