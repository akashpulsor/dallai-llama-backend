package com.dalai.llama.tenant.leadmanagement.email;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The relay only takes mail whose envelope sender is the shared mailbox; the brand still sees the creator. */
class SmtpCreatorEmailSenderTest {

    @Test
    void envelopeIsTheSharedMailboxWhileTheBrandSeesAndRepliesToTheCreator() throws Exception {
        UUID creator = UUID.randomUUID();
        JavaMailSender smtp = mock(JavaMailSender.class);
        when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        CreatorEmailIdentityService identities = mock(CreatorEmailIdentityService.class);
        when(identities.findByTenant(creator)).thenReturn(Optional.of(CreatorEmailIdentity.builder().tenantId(creator)
                .email("cr_abc@partner.dalaillama.in").displayName("Riya Motion").build()));
        SmtpCreatorEmailSender sender = new SmtpCreatorEmailSender(smtp, identities, "outreach@dalaillama.in", "outreach@dalaillama.in", true);

        EmailSendResult result = sender.send(CreatorEmailMessage.builder().fromCreatorId(creator).to(List.of("buyer@hearth.example"))
                .subject("A film for you").bodyText("Hi").build());

        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(sent.capture());
        assertThat(sent.getValue()).isInstanceOf(SMTPMessage.class);
        assertThat(((SMTPMessage) sent.getValue()).getEnvelopeFrom()).isEqualTo("outreach@dalaillama.in");
        assertThat(((InternetAddress) sent.getValue().getFrom()[0]).getAddress()).isEqualTo("cr_abc@partner.dalaillama.in");
        assertThat(((InternetAddress) sent.getValue().getFrom()[0]).getPersonal()).isEqualTo("Riya Motion");
        assertThat(((InternetAddress) sent.getValue().getReplyTo()[0]).getAddress()).isEqualTo("cr_abc@partner.dalaillama.in");
        assertThat(sent.getValue().getSubject()).isEqualTo("A film for you");
        // No Sender header: the brand's inbox shows the creator's alias, not "on behalf of outreach@".
        assertThat(sent.getValue().getHeader("Sender")).isNull();
    }
}
