package com.dalai.llama.tenant.leadmanagement.notify;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mail From the outreach domain is DKIM-signed with our key; anything else, or no key, goes as it is. */
class MailSignerTest {

    private static final String KEY = key();

    @Test
    void signsMailFromTheOutreachDomainAsThatDomain() throws Exception {
        String raw = written(new MailSigner("partner.dalaillama.in", "dl1", KEY).prepare(mail("Akash <cr_abc@partner.dalaillama.in>")));

        assertThat(raw).contains("DKIM-Signature:");
        String signature = raw.substring(raw.indexOf("DKIM-Signature:"), raw.indexOf("DKIM-Signature:") + 400).replaceAll("\s", "");
        assertThat(signature).contains("d=partner.dalaillama.in", "s=dl1", "a=rsa-sha256", "c=relaxed/relaxed");
    }

    @Test
    void leavesOtherDomainsAndKeylessSetupsUnsigned() throws Exception {
        assertThat(written(new MailSigner("partner.dalaillama.in", "dl1", KEY).prepare(mail("Dalai Llama <outreach@dalaillama.in>"))))
                .doesNotContain("DKIM-Signature:");
        MailSigner off = new MailSigner("partner.dalaillama.in", "dl1", "");
        assertThat(off.enabled()).isFalse();
        assertThat(written(off.prepare(mail("brands@partner.dalaillama.in")))).doesNotContain("DKIM-Signature:");
    }

    @Test
    void takesTheKeyAsPemToo_andRefusesABrokenOne() {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + KEY.replaceAll("(.{64})", "$1\n") + "\n-----END PRIVATE KEY-----\n";
        assertThat(new MailSigner("partner.dalaillama.in", "dl1", pem).enabled()).isTrue();
        assertThatThrownBy(() -> new MailSigner("partner.dalaillama.in", "dl1", "bm90IGEga2V5")).isInstanceOf(IllegalStateException.class);
    }

    private static MimeMessage mail(String from) throws Exception {
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        mime.setFrom(new InternetAddress(from));
        mime.setRecipients(jakarta.mail.Message.RecipientType.TO, "brand@x.example");
        mime.setSubject("A video I made recently");
        mime.setText("Hi,\n\nHere's a film.\n", StandardCharsets.UTF_8.name());
        return mime;
    }

    private static String written(MimeMessage mime) throws Exception {
        mime.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mime.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String key() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
