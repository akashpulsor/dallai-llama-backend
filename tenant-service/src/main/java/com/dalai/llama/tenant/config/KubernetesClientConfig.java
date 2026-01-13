package com.dalai.llama.tenant.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesClientConfig {

    @Value("${kubernetes.master-url:}")
    private String masterUrl;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    @Value("${kubernetes.trust-certs:true}")
    private boolean trustCerts;

    @Bean
    public KubernetesClient kubernetesClient() {
        Config config;

        if (masterUrl != null && !masterUrl.isEmpty()) {
            // Outside cluster - explicit config
            config = new ConfigBuilder()
                    .withMasterUrl(masterUrl)
                    .withNamespace(namespace)
                    .withTrustCerts(trustCerts)
                    .build();
        } else {
            // Inside cluster - auto-detect from service account
            config = new ConfigBuilder()
                    .withNamespace(namespace)
                    .build();
        }

        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }
}