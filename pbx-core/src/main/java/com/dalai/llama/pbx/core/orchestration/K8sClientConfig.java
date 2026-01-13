package com.dalai.llama.pbx.core.orchestration;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class K8sClientConfig {

    @Bean
    public KubernetesClient kubernetesClient(
            @Value("${k8s.master:}") String masterUrl) {

        Config cfg = (masterUrl == null || masterUrl.isBlank())
                ? Config.autoConfigure(null)
                : new ConfigBuilder()
                .withMasterUrl(masterUrl)
                .build();;

        return new DefaultKubernetesClient(cfg);
    }
}
