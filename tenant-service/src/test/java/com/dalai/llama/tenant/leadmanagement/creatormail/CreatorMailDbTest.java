package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendResult;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.TemplateRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/** Ops mail to creators on a real Postgres (V58): only active creator accounts receive it, {name}
 * becomes each creator's first name, a test goes to one address, and every send is logged. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CreatorMailService.class, CreatorMailStore.class, MailRenderer.class})
class CreatorMailDbTest extends ShowcaseDbTestSupport {

    @Autowired
    private CreatorMailService service;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private PlatformMailer mailer;

    private UUID asha;
    private UUID ravi;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ops_creator_mail_send");
        jdbc.update("DELETE FROM ops_creator_mail_template");
        // Earlier tests' accounts stop being active creators (other tables point at tenants).
        jdbc.update("UPDATE tenants SET status = 'CREATED' WHERE status = 'ACTIVE'");
        clearInvocations(mailer);
        asha = account("Asha Rao", "asha@example.com", "CREATOR", "ACTIVE");
        ravi = account("Ravi", "ravi@example.com", "CREATOR", "ACTIVE");
        account("Brand Owner", "brand@example.com", "BRAND", "ACTIVE");
        account("Pending Creator", "pending@example.com", "CREATOR", "CREATED");
    }

    @Test
    void sendsToEveryActiveCreatorWithTheirFirstName() {
        Template template = service.create(new TemplateRequest("Tips", "Hi {name}, a tip", "Hello {name},\n\nSee https://dalaillama.in/tips.", null));

        SendResult result = service.send(new SendRequest(template.id(), null, null));

        assertThat(result).isEqualTo(new SendResult(Audience.ALL, 2));
        List<PlatformMailer.Mail> sent = sent(2);
        assertThat(sent).extracting(PlatformMailer.Mail::to).containsExactlyInAnyOrder("asha@example.com", "ravi@example.com");
        PlatformMailer.Mail toAsha = sent.stream().filter(m -> m.to().equals("asha@example.com")).findFirst().orElseThrow();
        assertThat(toAsha.subject()).isEqualTo("Hi Asha, a tip");
        assertThat(toAsha.text()).contains("Hello Asha,");
        assertThat(toAsha.html()).contains("<a href=\"https://dalaillama.in/tips\"").contains("FOR CREATORS");
        assertThat(service.templates().get(0).lastSentAt()).isNotNull();
    }

    @Test
    void sendsOnlyToChosenCreators() {
        Template template = service.create(new TemplateRequest("News", "News", "Body", null));

        SendResult result = service.send(new SendRequest(template.id(), List.of(ravi), null));

        assertThat(result).isEqualTo(new SendResult(Audience.CHOSEN, 1));
        assertThat(sent(1)).extracting(PlatformMailer.Mail::to).containsExactly("ravi@example.com");
    }

    @Test
    void testSendGoesToOneAddressAndDoesNotCountAsSent() {
        Template template = service.create(new TemplateRequest("News", "Hi {name}", "<b>Body</b>", null));

        service.send(new SendRequest(template.id(), List.of(asha), "ops@example.com"));

        PlatformMailer.Mail mail = sent(1).get(0);
        assertThat(mail.to()).isEqualTo("ops@example.com");
        assertThat(mail.subject()).isEqualTo("Hi there");
        assertThat(mail.html()).contains("&lt;b&gt;Body&lt;/b&gt;");
        assertThat(service.templates().get(0).lastSentAt()).isNull();
        assertThat(service.recentSends()).extracting(CreatorMailStore.Send::audience).containsExactly(Audience.TEST);
    }

    @Test
    void switchedOffTemplateIsNotSent() {
        Template template = service.create(new TemplateRequest("Old", "Old", "Body", null));
        service.update(template.id(), new TemplateRequest("Old", "Old", "Body", false));

        assertThatThrownBy(() -> service.send(new SendRequest(template.id(), null, null))).isInstanceOf(ResponseStatusException.class);
    }

    private List<PlatformMailer.Mail> sent(int expected) {
        ArgumentCaptor<PlatformMailer.Mail> mails = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mails.capture());
        assertThat(mails.getAllValues()).hasSize(expected);
        return mails.getAllValues();
    }

    private UUID account(String name, String email, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email, account_type, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, name, "t-" + id.toString().substring(0, 8), name, email, type, status);
        return id;
    }
}
