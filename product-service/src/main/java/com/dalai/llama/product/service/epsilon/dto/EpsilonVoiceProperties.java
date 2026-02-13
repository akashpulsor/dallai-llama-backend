package com.dalai.llama.product.service.epsilon.dto;



import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "epsilon.voice")
@Validated
public class EpsilonVoiceProperties {


    @NotBlank
    private String apiUrl;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private Defaults defaults = new Defaults();

    @Data
    public static class Defaults {
        private String type = "LOCAL";
        private String city = "UNKNOWN";
        private String monthlyFee = "0";
        private String setupFee = "0";
        private String inboundPrice = "0";
        private String outboundPrice = "0";
        private String currency = "USD";
    }
}

