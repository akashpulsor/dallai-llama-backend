package com.dalai.llama.creator;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(CreatorProperties.class)
public class CreatorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreatorServiceApplication.class, args);
    }
}
