package org.springframework.boot.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestTemplate;

@EnableJpaAuditing
@SpringBootApplication
public class CrmApplication {

    public static void main(String[] args) {
        // Ensure logs directory exists at startup (create only if not exists)
        java.io.File logDir = new java.io.File("logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        SpringApplication.run(CrmApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }


}
