package com.dalai.llama.billing.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Hetzner blocks outbound 465, so the shipped mail defaults must be 587 + STARTTLS (the same
 * relay settings tenant-service uses) or every billing mail times out on the box. */
class MailConfigTest {

    @Test
    void shippedDefaultsUseStartTlsOn587() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);

        assertThat(yml.getProperty("spring.mail.port")).hasToString("${SMTP_PORT:587}");
        assertThat(yml.getProperty("spring.mail.properties.mail.smtp.ssl.enable")).isEqualTo(false);
        assertThat(yml.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo(true);
        assertThat(yml.getProperty("spring.mail.properties.mail.smtp.starttls.required")).isEqualTo(true);
    }
}
