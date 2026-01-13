package com.dalai.llama.product.service.didww;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "didww")
@Validated
public class DidwwProperties {

    /**
     * Base URL for DIDWW API
     * Example: https://api.didww.com/v3
     */
    @NotBlank
    private String apiUrl;

    /**
     * API key used for Authorization: Bearer <apiKey>
     */
    @NotBlank
    private String apiKey;

    /**
     * Shared secret for validating DIDWW webhooks
     */
    @NotBlank
    private String webhookSecret;

    /**
     * Enable/disable background sync jobs
     */
    private boolean syncEnabled = true;

    /**
     * Interval (in minutes) for DIDWW sync job
     */
    private int syncIntervalMinutes = 60;
}
