package com.dalai.llama.product.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "didww")
public class DidwwConfig {
    private String apiUrl;
    private String apiKey;
    private String webhookSecret;
    private boolean syncEnabled;
    private int syncIntervalMinutes;
}
