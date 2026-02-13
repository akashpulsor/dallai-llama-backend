package com.dalai.llama.tenant.service.provisioning.telecom.k8s;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;

public interface K8sDeployer {
    void deploy(String namespace, Tenant tenant, TelecomStackConfig config);
    void delete(String namespace);
    boolean isHealthy(String namespace);
    String name();
}