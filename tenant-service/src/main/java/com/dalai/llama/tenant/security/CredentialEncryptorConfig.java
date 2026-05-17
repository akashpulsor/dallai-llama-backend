package com.dalai.llama.tenant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

@Configuration
public class CredentialEncryptorConfig {

    @Value("${dalaillama.credentials.encryption-key:}")
    private String base64Key;

    @Bean
    public CredentialEncryptor credentialEncryptor() {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "DALAI_CRED_ENCRYPTION_KEY is not set. " +
                    "Generate with: openssl rand -base64 32");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 32) {
            throw new IllegalStateException(
                    "DALAI_CRED_ENCRYPTION_KEY must be exactly 32 bytes (256-bit). Got " + key.length);
        }
        return new CredentialEncryptor(key);
    }
}
