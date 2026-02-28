package com.dalai.llama.tenant.config;

import io.fabric8.istio.client.IstioClient;
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

        // Let Fabric8 auto-detect EVERYTHING first
        Config baseConfig = Config.autoConfigure(null);

        ConfigBuilder builder = new ConfigBuilder(baseConfig)
                .withNamespace(namespace)
                .withTrustCerts(trustCerts);

        // Only override master if explicitly provided
        if (masterUrl != null && !masterUrl.isBlank()) {
            builder.withMasterUrl(masterUrl);
        }

        return new KubernetesClientBuilder()
                .withConfig(builder.build())
                .build();
    }

    @Bean
    public IstioClient istioClient(KubernetesClient kubernetesClient) {
        return kubernetesClient.adapt(IstioClient.class);
    }
}
